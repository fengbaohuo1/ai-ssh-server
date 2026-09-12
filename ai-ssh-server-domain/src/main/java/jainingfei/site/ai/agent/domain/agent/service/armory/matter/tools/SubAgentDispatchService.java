package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 子智能体任务执行器——面向"运行期规划出来的任务列表"执行单个 {@link DynamicTask}（教程 2-10）。
 *
 * <p>与 {@link SubAgentDispatchTool} 的分工：
 * <pre>
 *   SubAgentDispatchTool   父智能体点名派发一个固定子智能体（工具名 = 子智能体名）
 *   SubAgentDispatchService 编排器 / 批量派发按任务执行（按名查找、超时、重试、状态回写）
 * </pre>
 *
 * <p>单次执行的关键动作：
 * <ol>
 *   <li>按 {@code appName + agentName} 从 {@link AgentCatalog} 查找子智能体（未命中全局兜底）</li>
 *   <li>新建 Runner（appName = 子智能体名，不挂插件）+ <b>显式创建带初始 state 的子会话</b>，
 *       把父会话绑定的 connectionId 写进 state，让子智能体的 SSH 工具在正确连接上执行</li>
 *   <li>阻塞收集事件 → {@link SubAgentResultExtractor} 收敛结论 → 回写 task</li>
 *   <li>执行期失败按 2^n 秒指数退避重试（封顶 30 秒）；"子智能体不存在""结果为空"属确定性错误不重试</li>
 *   <li>finally 回收子会话，避免常驻内存的会话服务里堆积一次性会话</li>
 * </ol>
 */
@Slf4j
@Service
public class SubAgentDispatchService {

    /** 子会话的固定用户标识（子会话不参与用户级记忆，仅用于会话隔离） */
    public static final String SUBAGENT_USER_ID = "subagent-user";

    /** 重试退避间隔上限（秒） */
    private static final long MAX_BACKOFF_SECONDS = 30;

    /** 单任务默认超时（秒） */
    private static final int DEFAULT_TASK_TIMEOUT_SECONDS = 120;

    private final AgentCatalog agentCatalog;
    private final CustomRunnerFactory runnerFactory;
    private final BaseSessionService sessionService;

    public SubAgentDispatchService(AgentCatalog agentCatalog,
                                   CustomRunnerFactory runnerFactory,
                                   BaseSessionService sessionService) {
        this.agentCatalog = agentCatalog;
        this.runnerFactory = runnerFactory;
        this.sessionService = sessionService;
    }

    /**
     * 执行单个任务并原地回写状态（成功 → COMPLETED/result；失败 → FAILED/error；attempts 记录实际尝试次数）。
     *
     * @param context 派发上下文（appName / 父智能体名 / 终端会话）
     * @param task    任务（含 agentName、request、超时与重试配置）
     */
    public void execute(AgentExecutionContext context, DynamicTask task) {
        task.setStatus(TaskStatus.RUNNING);

        BaseAgent agent = agentCatalog.find(context.getAppName(), task.getAgentName())
                .or(() -> agentCatalog.findByName(task.getAgentName()))
                .orElse(null);
        if (agent == null) {
            // 确定性错误：重试一百次也不会有，直接失败且 attempts 保持 0（不浪费重试与退避时间）
            task.setStatus(TaskStatus.FAILED);
            task.setError("agent not found: " + task.getAgentName());
            log.warn("子Agent不存在 | agent={} | appName={} | task={}",
                    task.getAgentName(), context.getAppName(), task.getTaskId());
            return;
        }

        int maxRetries = task.getMaxRetries() == null ? 0 : Math.max(0, Math.min(3, task.getMaxRetries()));
        int maxAttempts = 1 + maxRetries;
        int timeoutSeconds = task.getTimeoutSeconds() == null
                ? DEFAULT_TASK_TIMEOUT_SECONDS : Math.max(1, task.getTimeoutSeconds());

        String invocationId = UUID.randomUUID().toString();
        String appName = agent.name();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            task.setAttempts(attempt);
            String sessionId = "subagent-" + invocationId + "-" + attempt;
            long startedAt = System.currentTimeMillis();
            try {
                Runner runner = runnerFactory.createForSubAgent(agent);
                createSubSession(runner, appName, sessionId, context.getTerminalSessionId());

                log.info("子Agent执行开始 | agent={} | task={} | attempt={}/{} | timeout={}s | sessionId={} | terminalSession={}",
                        task.getAgentName(), task.getTaskId(), attempt, maxAttempts, timeoutSeconds, sessionId,
                        context.getTerminalSessionId());
                Content content = Content.fromParts(Part.fromText(task.getRequest()));
                List<Event> events = runner.runAsync(SUBAGENT_USER_ID, sessionId, content,
                                RunConfig.builder().autoCreateSession(true).build())
                        .timeout(timeoutSeconds, TimeUnit.SECONDS)
                        .toList()
                        .blockingGet();

                String result = SubAgentResultExtractor.pickFinalText(events);
                if (result.isBlank()) {
                    // 结果为空 = 模型没给出结论：不重试（变更类任务重跑有副作用），直接失败让父智能体决策
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("empty sub agent result");
                    log.warn("子Agent结果为空 | agent={} | task={} | events={}",
                            task.getAgentName(), task.getTaskId(), events.size());
                    return;
                }

                task.setResult(result);
                task.setStatus(TaskStatus.COMPLETED);
                log.info("子Agent执行完成 | agent={} | task={} | attempts={} | 耗时={}ms | resultLength={}",
                        task.getAgentName(), task.getTaskId(), attempt,
                        System.currentTimeMillis() - startedAt, result.length());
                return;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 5));
                    log.warn("子Agent执行失败，{}秒后重试 | agent={} | task={} | attempt={}/{} | error={}",
                            backoff, task.getAgentName(), task.getTaskId(), attempt, maxAttempts, e.getMessage());
                    awaitBackoff(backoff);
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("attempts=" + attempt + " | " + e.getMessage());
                    log.error("子Agent执行失败（重试耗尽） | agent={} | task={} | attempts={} | 耗时={}ms",
                            task.getAgentName(), task.getTaskId(), attempt,
                            System.currentTimeMillis() - startedAt, e);
                }
            } finally {
                recycleSession(appName, sessionId);
            }
        }
    }

    /** 显式创建子会话并写入初始 state（终端会话 ID 在这里进入子会话，供其 SSH 工具读取） */
    private void createSubSession(Runner runner, String appName, String sessionId, String terminalSessionId) {
        ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
        if (terminalSessionId != null && !terminalSessionId.isBlank()) {
            initialState.put(SshExecuteAdkTool.STATE_KEY_TERMINAL_SESSION, terminalSessionId);
        }
        runner.sessionService()
                .createSession(appName, SUBAGENT_USER_ID, initialState, sessionId)
                .blockingGet();
    }

    /** 回收子会话：会话服务是常驻内存的，一次性子会话不回收会随派发次数无界增长 */
    private void recycleSession(String appName, String sessionId) {
        try {
            sessionService.deleteSession(appName, SUBAGENT_USER_ID, sessionId).blockingAwait();
            log.debug("子Agent会话回收 | appName={} | sessionId={}", appName, sessionId);
        } catch (Exception e) {
            log.warn("子Agent会话回收失败 | appName={} | sessionId={} | {}", appName, sessionId, e.getMessage());
        }
    }

    /**
     * 退避等待（protected 而非 private：单元测试覆写它以避免真实睡眠，
     * 重试语义的验证不该让测试跑上好几秒）
     */
    protected void awaitBackoff(long seconds) {
        sleepQuietly(seconds);
    }

    /** 退避等待：被中断时恢复中断标记并直接返回（让位给外层超时/取消语义） */
    private void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

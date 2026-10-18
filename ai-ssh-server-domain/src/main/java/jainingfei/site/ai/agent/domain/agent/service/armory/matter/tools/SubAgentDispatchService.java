package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;
import jainingfei.site.ai.agent.domain.agent.support.AgentTaskContextPropagator;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jainingfei.site.ai.agent.domain.agent.support.ReActStepSink;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * <p><b>超时语义（本轮加固）</b>：旧实现用 Rx 的 {@code timeout} 运算符——超时只让下游放弃，
 * 上游 flowable 并没有被取消，ADK 还在后台跑模型、SSH 命令也还在执行，线程与连接都悬挂。
 * 现在改为"订阅放到 io 调度器 + 用 {@link CountDownLatch} 等完成信号"，超时即
 * {@link Disposable#dispose()}（真正向下游传播取消、终止上游），并在 finally 里回收子会话。
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

    /**
     * 订阅线程池：ADK 的事件流要在独立线程上跑，本线程才能"等超时后 dispose"。
     *
     * <p>不用 Rx 自带的 {@link Schedulers#io()}：它不传播 {@code ThreadLocal}，子智能体的 SSH 工具
     * 写审计时会读不到父上下文的 userId / connectionId。这里用 daemon 缓存池 + 上下文包装执行器，
     * 等价于 io 调度器的线程复用行为，但任务进入执行线程前会恢复父线程的审计快照与命令熔断作用域。
     */
    private static final ExecutorService SUBSCRIBE_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "subagent-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    /** 订阅调度器：被调度的任务在<b>提交该任务的线程</b>上快照上下文，执行线程内恢复、结束还原 */
    private static final Scheduler SUBSCRIBE_SCHEDULER = Schedulers.from(
            (Executor) command -> SUBSCRIBE_EXECUTOR.execute(
                    AgentTaskContextPropagator.captureCurrent(command)));

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
     * <p>执行期绑定 {@link AgentTaskContextPropagator} 作用域：审计 userId / 连接上下文可被子智能体
     * 及其工具读取，命令熔断也按"本任务所属会话/智能体"隔离。调用方（编排器）已在线程池边界包装过时，
     * 这里是幂等的覆盖 + 还原。
     *
     * @param context 派发上下文（appName / 父智能体名 / 终端会话）
     * @param task    任务（含 agentName、request、超时与重试配置）
     */
    public void execute(AgentExecutionContext context, DynamicTask task) {
        try (AgentTaskContextPropagator.Scope ignored =
                     AgentTaskContextPropagator.bind(context, task.getAgentName())) {
            doExecute(context, task);
        }
    }

    /** 真正的执行逻辑（与上下文绑定解耦，便于单测与后续替换） */
    private void doExecute(AgentExecutionContext context, DynamicTask task) {
        task.setStatus(TaskStatus.RUNNING);

        BaseAgent agent = agentCatalog.find(context.getAppName(), task.getAgentName())
                .or(() -> agentCatalog.findByName(task.getAgentName()))
                .orElse(null);
        if (agent == null) {
            // 确定性错误：重试一百次也不会有，直接失败且 attempts 保持 0（不浪费重试与退避时间）
            task.setStatus(TaskStatus.FAILED);
            task.setError("agent not found: " + task.getAgentName());
            // 界面上也要有始有终：先补一张派发卡片再给终态，否则这一格永远是空白
            reportTaskStarted(context, task, taskStepId(task, context), 1);
            reportTaskFinished(context, task, taskStepId(task, context), false);
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

        // 步骤树节点 id：单派发时父步骤（工具调用）与任务同 id，卡片原地升级为派发卡片；
        // 批量/规划派发时每个任务一个 id，嵌在批量卡片下面
        String stepId = taskStepId(task, context);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            task.setAttempts(attempt);
            String sessionId = "subagent-" + invocationId + "-" + attempt;
            long startedAt = System.currentTimeMillis();
            reportTaskStarted(context, task, stepId, maxAttempts);
            try {
                Runner runner = runnerFactory.createForSubAgent(agent);
                createSubSession(runner, appName, sessionId, context.getTerminalSessionId());

                log.info("子Agent执行开始 | agent={} | task={} | attempt={}/{} | timeout={}s | sessionId={} | terminalSession={}",
                        task.getAgentName(), task.getTaskId(), attempt, maxAttempts, timeoutSeconds, sessionId,
                        context.getTerminalSessionId());
                Content content = Content.fromParts(Part.fromText(task.getRequest()));
                // 边收边发：子智能体的事件既用于收敛结论，也实时翻译成步骤上报给前端
                // （旧实现 toList().blockingGet() 把事件一次吃光，界面上看不到子智能体做了什么）
                List<Event> events = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch finished = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Disposable disposable = null;
                try {
                    // 订阅放到 SUBSCRIBE_SCHEDULER：本线程只负责等信号。这样"等超时"与"上游执行"在不同
                    // 线程上，超时才能通过 dispose() 真正取消上游（若同步订阅，本线程会被流卡住，超时形同虚设）。
                    // 该调度器带上下文包装：执行线程上恢复父审计快照与命令熔断作用域，结束后还原池线程原值，
                    // 因此子智能体及其 SSH 工具读到的仍是父上下文的 userId / connectionId。
                    disposable = runner.runAsync(SUBAGENT_USER_ID, sessionId, content,
                                    RunConfig.builder().autoCreateSession(true).build())
                            .subscribeOn(SUBSCRIBE_SCHEDULER)
                            .subscribe(event -> {
                                        events.add(event);
                                        reportChildEvent(context, event, stepId, agent.name());
                                    },
                                    error -> {
                                        failure.set(error);
                                        finished.countDown();
                                    },
                                    finished::countDown);

                    if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                        disposable.dispose();
                        log.warn("子Agent执行超时，已取消上游 | agent={} | task={} | attempt={}/{} | timeout={}s | sessionId={}",
                                task.getAgentName(), task.getTaskId(), attempt, maxAttempts, timeoutSeconds, sessionId);
                        throw new IllegalStateException(
                                "子Agent执行超时（timeout=" + timeoutSeconds + "s）：已取消上游执行");
                    }

                    Throwable streamError = failure.get();
                    if (streamError != null) {
                        throw new IllegalStateException("子Agent执行失败：" + streamError.getMessage(), streamError);
                    }

                    String result = SubAgentResultExtractor.pickFinalText(events);
                    if (result.isBlank()) {
                        // 结果为空 = 模型没给出结论：不重试（变更类任务重跑有副作用），直接失败让父智能体决策
                        task.setStatus(TaskStatus.FAILED);
                        task.setError("empty sub agent result");
                        reportTaskFinished(context, task, stepId, false);
                        log.warn("子Agent结果为空 | agent={} | task={} | events={}",
                                task.getAgentName(), task.getTaskId(), events.size());
                        return;
                    }

                    task.setResult(SubAgentResultExtractor.appendCommandTrace(result, events));
                    task.setStatus(TaskStatus.COMPLETED);
                    reportTaskFinished(context, task, stepId, true);
                    log.info("子Agent执行完成 | agent={} | task={} | attempts={} | 耗时={}ms | resultLength={}",
                            task.getAgentName(), task.getTaskId(), attempt,
                            System.currentTimeMillis() - startedAt, task.getResult().length());
                    return;
                } catch (InterruptedException interrupted) {
                    // 被外层取消（线程池关闭 / 调用方中断）：恢复中断标记并退出，不留悬挂订阅
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("子Agent执行被中断", interrupted);
                } finally {
                    if (disposable != null && !disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 5));
                    log.warn("子Agent执行失败，{}秒后重试 | agent={} | task={} | attempt={}/{} | error={}",
                            backoff, task.getAgentName(), task.getTaskId(), attempt, maxAttempts, e.getMessage());
                    awaitBackoff(backoff);
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("attempts=" + attempt + " | " + e.getMessage());
                    reportTaskFinished(context, task, stepId, false);
                    log.error("子Agent执行失败（重试耗尽） | agent={} | task={} | attempts={} | 耗时={}ms",
                            task.getAgentName(), task.getTaskId(), attempt,
                            System.currentTimeMillis() - startedAt, e);
                }
            } finally {
                recycleSession(appName, sessionId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  步骤上报（SSE 步骤协议 v2：把子智能体的动作挂到父回合的卡片下面）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 任务的步骤节点 id：统一用 {@code taskId}。
     *
     * <p>单派发时工具层把 {@code taskId} 直接取成本次工具调用 id，
     * 于是"父级工具卡片"与"任务节点"是同一个 id → 前端按 id upsert 后原地升级成派发卡片，
     * 不会多出一层同名嵌套；批量/规划派发时每个任务一个 id，挂在批量卡片下面。
     */
    private static String taskStepId(DynamicTask task, AgentExecutionContext context) {
        return task.getTaskId() != null && !task.getTaskId().isBlank()
                ? task.getTaskId()
                : context.getStepId();
    }

    /** 任务开始：派发卡片进入"运行中"（带子智能体名、任务指令、重试次数） */
    private void reportTaskStarted(AgentExecutionContext context, DynamicTask task,
                                   String stepId, int maxAttempts) {
        ReActStepSink sink = context.getStepSink();
        if (sink == null) {
            return;
        }
        sink.emit(StepEvents.with(
                StepEvents.toolCall(stepId, task.getAgentName(),
                        Map.of("request", task.getRequest() == null ? "" : task.getRequest()),
                        StepEvents.K_AGENT, context.getStepId()),
                StepEvents.F_ATTEMPT, task.getAttempts() == null ? 1 : task.getAttempts()));
    }

    /** 任务终态：成功/失败 + 结论或错误 + 耗时（挂在父工具调用下，与任务开始同一 id） */
    private void reportTaskFinished(AgentExecutionContext context, DynamicTask task,
                                    String stepId, boolean success) {
        ReActStepSink sink = context.getStepSink();
        if (sink == null) {
            return;
        }
        Object result = success
                ? Map.of("success", true, "result", task.getResult() == null ? "" : task.getResult())
                : Map.of("success", false, "error", task.getError() == null ? "unknown error" : task.getError());
        Map<String, Object> step = StepEvents.toolResult(stepId, task.getAgentName(), result, success, null);
        sink.emit(StepEvents.with(step, StepEvents.F_PARENT, context.getStepId()));
    }

    /**
     * 子智能体自己的事件 → 嵌套步骤（{@code p = 任务步骤 id}）。
     *
     * <p>只上报工具调用/结果：子智能体的正文是"它自己的思考"，混进父回合的聊天正文会污染回答，
     * 它真正要交付的东西已经由 {@link SubAgentResultExtractor} 收敛进任务的 result。
     */
    private void reportChildEvent(AgentExecutionContext context, Event event,
                                  String parentStepId, String childAgentName) {
        ReActStepSink sink = context.getStepSink();
        if (sink == null) {
            return;
        }
        try {
            for (EventTextUtil.ToolCallView call : EventTextUtil.functionCalls(event)) {
                Map<String, Object> step = StepEvents.toolCall(call.id(), call.name(), call.args(),
                        StepEvents.K_TOOL, parentStepId);
                sink.emit(StepEvents.with(step, StepEvents.F_AGENT, childAgentName));
            }
            for (EventTextUtil.ToolResultView result : EventTextUtil.functionResponses(event)) {
                Map<String, Object> response = result.response();
                boolean success = !Boolean.FALSE.equals(response.get("success"));
                Map<String, Object> step = StepEvents.toolResult(result.id(), result.name(), response,
                        success, null);
                // 结果同样要挂回发起它的那一步（缺 p 会变成顶层孤儿卡片）
                sink.emit(StepEvents.with(
                        StepEvents.with(step, StepEvents.F_PARENT, parentStepId),
                        StepEvents.F_AGENT, childAgentName));
            }
        } catch (Exception e) {
            log.debug("子智能体步骤上报失败 agent={}: {}", childAgentName, e.getMessage());
        }
    }

    /** 显式创建子会话并写入初始 state（终端会话 ID 在这里进入子会话，供其 SSH 工具读取） */
    private void createSubSession(Runner runner, String appName, String sessionId, String terminalSessionId) {
        ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
        if (terminalSessionId != null && !terminalSessionId.isBlank()) {
            initialState.put(SshSessionBindings.STATE_KEY_TERMINAL_SESSION, terminalSessionId);
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

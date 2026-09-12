package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体任务执行器（SubAgentDispatchService）单元测试——真实 Runner + 测试替身子智能体，无模型无网络。
 *
 * <p>覆盖：成功收敛、确定性错误不重试、默认不重试、重试后恢复、重试耗尽、
 * 空结果判失败、终端会话写入子会话初始 state、子会话跑完回收。
 */
public class SubAgentDispatchServiceTest {

    private static final String APP_NAME = "sshAgent";

    private InMemorySessionService sessionService;
    private AgentCatalog catalog;
    private SubAgentDispatchService service;

    /** 测试 Runner 装配：真实 Runner + InMemory 会话服务 + 测试替身子智能体 */
    static class TestRunnerFactory extends CustomRunnerFactory {
        private final InMemorySessionService sessionService;

        TestRunnerFactory(InMemorySessionService sessionService) {
            this.sessionService = sessionService;
        }

        @Override
        public Runner createForSubAgent(BaseAgent subAgent) {
            return Runner.builder()
                    .app(App.builder().name(subAgent.name()).rootAgent(subAgent).plugins(List.of()).build())
                    .artifactService(new InMemoryArtifactService())
                    .sessionService(sessionService)
                    .memoryService(new InMemoryMemoryService())
                    .build();
        }
    }

    @BeforeEach
    public void setUp() {
        sessionService = new InMemorySessionService();
        catalog = new AgentCatalog();
        // 覆写退避等待：重试语义的验证不该让测试真等 2~6 秒
        service = new SubAgentDispatchService(catalog, new TestRunnerFactory(sessionService), sessionService) {
            @Override
            protected void awaitBackoff(long seconds) {
                // 不真实等待
            }
        };
    }

    private AgentExecutionContext context(String terminalSessionId) {
        return AgentExecutionContext.builder()
                .userId("user-1")
                .appName(APP_NAME)
                .agentName("sshOperator")
                .parentSessionId("inv-parent")
                .terminalSessionId(terminalSessionId)
                .build();
    }

    private DynamicTask task(String agentName) {
        return DynamicTask.builder().taskId("t1").agentName(agentName).request("检查磁盘").build();
    }

    @Test
    public void test_success_completes_task_and_recycles_session() {
        StubSubAgent agent = StubSubAgent.replying("sshDiagnosisAgent", "诊断正常");
        catalog.register(APP_NAME, Map.of("sshDiagnosisAgent", agent));

        DynamicTask task = task("sshDiagnosisAgent");
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("诊断正常", task.getResult());
        assertEquals(1, task.getAttempts());

        String sessionId = agent.lastSessionId();
        assertTrue(sessionId.startsWith("subagent-"), sessionId);
        // 终端会话透传：子会话初始 state 带上父会话绑定的 connectionId
        assertEquals("conn-1", agent.stateOf(sessionId).get(SshExecuteAdkTool.STATE_KEY_TERMINAL_SESSION));
        // 子会话跑完回收（内存会话服务不会无界增长）
        assertNull(sessionService.getSession("sshDiagnosisAgent", "subagent-user", sessionId, Optional.empty())
                .blockingGet());
    }

    @Test
    public void test_agent_not_found_fails_without_retry() {
        catalog.register(APP_NAME, Map.of("sshDiagnosisAgent", StubSubAgent.replying("sshDiagnosisAgent", "ok")));

        DynamicTask task = task("ghostAgent");
        task.setMaxRetries(3);
        long startedAt = System.currentTimeMillis();
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertTrue(task.getError().contains("agent not found"), task.getError());
        assertEquals(0, task.getAttempts(), "确定性错误不该计入尝试次数");
        assertTrue(System.currentTimeMillis() - startedAt < 1000, "确定性错误应立即返回，不等待退避");
    }

    @Test
    public void test_failure_without_retry_by_default() {
        StubSubAgent agent = StubSubAgent.failing("sshChangeAgent", "命令执行异常");
        catalog.register(APP_NAME, Map.of("sshChangeAgent", agent));

        DynamicTask task = task("sshChangeAgent");
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertEquals(1, agent.runs(), "默认 maxRetries=0：只跑一次（写操作不幂等）");
    }

    @Test
    public void test_retry_recovers() {
        StubSubAgent agent = StubSubAgent.failTimesThenReply("sshChangeAgent", 2, "第三次成功");
        catalog.register(APP_NAME, Map.of("sshChangeAgent", agent));

        DynamicTask task = task("sshChangeAgent");
        task.setMaxRetries(2);
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("第三次成功", task.getResult());
        assertEquals(3, task.getAttempts());
        assertEquals(3, agent.runs());
        // 每次重试用独立会话：失败的执行不污染重试
        assertEquals(3, agent.sessionIds().stream().distinct().count(), agent.sessionIds().toString());
    }

    @Test
    public void test_retries_exhausted() {
        StubSubAgent agent = StubSubAgent.failing("sshChangeAgent", "一直失败");
        catalog.register(APP_NAME, Map.of("sshChangeAgent", agent));

        DynamicTask task = task("sshChangeAgent");
        task.setMaxRetries(2);
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(3, task.getAttempts());
        assertTrue(task.getError().startsWith("attempts=3"), task.getError());
    }

    @Test
    public void test_empty_result_fails_without_retry() {
        StubSubAgent agent = StubSubAgent.silent("sshChangeAgent");
        catalog.register(APP_NAME, Map.of("sshChangeAgent", agent));

        DynamicTask task = task("sshChangeAgent");
        task.setMaxRetries(2);
        service.execute(context("conn-1"), task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("empty sub agent result", task.getError());
        assertEquals(1, agent.runs(), "结果为空 = 模型没给结论，不重试（重跑变更类任务有副作用）");
    }

    @Test
    public void test_without_terminal_session_state_has_no_key() {
        StubSubAgent agent = StubSubAgent.replying("sshDiagnosisAgent", "ok");
        catalog.register(APP_NAME, Map.of("sshDiagnosisAgent", agent));

        DynamicTask task = task("sshDiagnosisAgent");
        service.execute(context(null), task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertNull(agent.stateOf(agent.lastSessionId()).get(SshExecuteAdkTool.STATE_KEY_TERMINAL_SESSION),
                "未绑定终端会话时不应写入该 state 键");
    }
}

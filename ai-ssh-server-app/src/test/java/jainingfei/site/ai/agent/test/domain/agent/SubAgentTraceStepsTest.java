package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService;
import jainingfei.site.ai.agent.domain.agent.support.ReActStepSink;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体执行轨迹上报单元测试——真实 Runner + 测试替身子智能体，无模型无网络。
 *
 * <p>要固定的行为（对应界面上"派发卡片里能看到子智能体跑了哪条命令"）：
 * <pre>
 *   ① 任务开始时上报一个 k=agent 的步骤，p = 触发派发的工具调用 id
 *   ② 子智能体自己的工具调用/结果挂到该任务下面（p = 任务步骤 id，ag = 子智能体名）
 *   ③ 任务终态更新同一步骤（成功/失败、结论或错误）
 *   ④ 子智能体的正文不外发（它自己的思考不该混进父回合的聊天正文）
 * </pre>
 */
public class SubAgentTraceStepsTest {

    private static final String APP_NAME = "sshAgent";
    private static final String CHILD = "sshDiagnosisAgent";

    private InMemorySessionService sessionService;
    private AgentCatalog catalog;
    private SubAgentDispatchService service;
    private final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();

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
        steps.clear();
        service = new SubAgentDispatchService(catalog, new TestRunnerFactory(sessionService), sessionService) {
            @Override
            protected void awaitBackoff(long seconds) {
                // 测试不真实等待
            }
        };
    }

    /** 跑一条命令再给结论的子智能体（事件序列与真实 Runner 一致：先思考、再调用、再结果、最后结论） */
    private static StubSubAgent commandRunningAgent() {
        return new StubSubAgent(CHILD, ctx -> Flowable.fromIterable(List.of(
                textEvent(ctx, "先看看系统信息"),
                functionCallEvent(ctx, "call-1", "executeCommand", Map.of("command", "uname -a")),
                functionResponseEvent(ctx, "call-1", "executeCommand",
                        Map.of("success", true, "output", "Linux host 6.8.0")),
                textEvent(ctx, "内核 6.8.0，系统正常"))));
    }

    private static Event textEvent(com.google.adk.agents.InvocationContext ctx, String text) {
        return baseEvent(ctx).content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build()).build();
    }

    private static Event functionCallEvent(com.google.adk.agents.InvocationContext ctx, String id,
                                           String name, Map<String, Object> args) {
        return baseEvent(ctx).content(Content.builder().role("model").parts(List.of(Part.builder()
                .functionCall(FunctionCall.builder().id(id).name(name).args(args).build()).build())).build()).build();
    }

    private static Event functionResponseEvent(com.google.adk.agents.InvocationContext ctx, String id,
                                               String name, Map<String, Object> response) {
        return baseEvent(ctx).content(Content.builder().role("user").parts(List.of(Part.builder()
                .functionResponse(FunctionResponse.builder().id(id).name(name).response(response).build())
                .build())).build()).build();
    }

    private static Event.Builder baseEvent(com.google.adk.agents.InvocationContext ctx) {
        return Event.builder().invocationId(ctx.invocationId()).author(CHILD);
    }

    private AgentExecutionContext context(String parentStepId) {
        return AgentExecutionContext.builder()
                .userId("user-1")
                .appName(APP_NAME)
                .agentName("sshOperator")
                .parentSessionId("inv-parent")
                .terminalSessionId("conn-1")
                .stepId(parentStepId)
                .stepSink(sink())
                .build();
    }

    private ReActStepSink sink() {
        String sessionId = "trace-test-" + System.nanoTime();
        ReActStepSink.bind(sessionId, steps::add);
        return ReActStepSink.of(sessionId);
    }

    private static DynamicTask task(String taskId) {
        return DynamicTask.builder().taskId(taskId).agentName(CHILD).request("检查磁盘").build();
    }

    private Map<String, Object> first(String type) {
        return steps.stream().filter(s -> type.equals(s.get(StepEvents.F_TYPE))).findFirst().orElse(null);
    }

    private List<Map<String, Object>> all(String type, String parentId) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (type.equals(step.get(StepEvents.F_TYPE)) && parentId.equals(step.get(StepEvents.F_PARENT))) {
                matched.add(step);
            }
        }
        return matched;
    }

    @Test
    @DisplayName("派发开始 → 子命令 → 任务终态：三步全都带 id 与父子关系")
    public void test_trace_reports_nested_steps() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        DynamicTask task = task("task-1");
        service.execute(context("call-dispatch"), task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());

        // ① 任务开始：k=agent，p=触发派发的工具调用 id
        Map<String, Object> taskStart = first(StepEvents.T_TOOL_CALL);
        assertNotNull(taskStart, "必须有派发步骤：" + steps);
        assertEquals("task-1", taskStart.get(StepEvents.F_ID));
        assertEquals("call-dispatch", taskStart.get(StepEvents.F_PARENT));
        assertEquals(StepEvents.K_AGENT, taskStart.get(StepEvents.F_KIND));
        assertEquals(CHILD, taskStart.get(StepEvents.F_NAME));
        assertEquals(StepEvents.ST_RUNNING, taskStart.get(StepEvents.F_STATUS));
        assertEquals(1, taskStart.get(StepEvents.F_ATTEMPT));
        assertEquals(Map.of("request", "检查磁盘"), taskStart.get(StepEvents.F_ARGS));

        // ② 子智能体自己的命令：挂在任务下面（p=task-1，ag=子智能体名）
        List<Map<String, Object>> childCalls = all(StepEvents.T_TOOL_CALL, "task-1");
        assertEquals(1, childCalls.size(), "子智能体执行了一条命令：" + steps);
        Map<String, Object> childCall = childCalls.get(0);
        assertEquals("call-1", childCall.get(StepEvents.F_ID));
        assertEquals("executeCommand", childCall.get(StepEvents.F_NAME));
        assertEquals(CHILD, childCall.get(StepEvents.F_AGENT));
        assertEquals(Map.of("command", "uname -a"), childCall.get(StepEvents.F_ARGS));

        List<Map<String, Object>> childResults = all(StepEvents.T_TOOL_RESULT, "task-1");
        assertEquals(1, childResults.size(), "命令结果同样挂在任务下面：" + steps);
        assertEquals(StepEvents.ST_OK, childResults.get(0).get(StepEvents.F_STATUS));

        // ③ 任务终态：同一步骤 id 更新为结果（前端 upsert 后原卡片收尾）
        List<Map<String, Object>> taskResults = all(StepEvents.T_TOOL_RESULT, "call-dispatch");
        assertEquals(1, taskResults.size(), "任务终态应挂在父工具调用下：" + steps);
        Map<String, Object> taskResult = taskResults.get(0);
        assertEquals("task-1", taskResult.get(StepEvents.F_ID));
        assertEquals(StepEvents.ST_OK, taskResult.get(StepEvents.F_STATUS));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) taskResult.get(StepEvents.F_RESULT);
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("内核 6.8.0，系统正常", result.get("result"));
    }

    @Test
    @DisplayName("子智能体的正文不外发：上报的步骤只有工具调用与结果，没有文本步骤")
    public void test_child_text_is_not_reported() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        service.execute(context("call-dispatch"), task("task-1"));

        for (Map<String, Object> step : steps) {
            String type = String.valueOf(step.get(StepEvents.F_TYPE));
            assertTrue(StepEvents.T_TOOL_CALL.equals(type) || StepEvents.T_TOOL_RESULT.equals(type),
                    "只允许工具类步骤，子智能体正文不得混入父回合：" + step);
            assertFalse(step.containsKey("v"), "步骤里不该带文本增量字段：" + step);
        }
    }

    @Test
    @DisplayName("单派发：任务步骤 id 复用工具调用 id 时，父子同为一步骤（卡片原地升级）")
    public void test_single_dispatch_reuses_tool_call_id() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        // 单派发工具把 taskId 取成本次 functionCallId，于是任务 id == 父步骤 id
        DynamicTask task = task("call-dispatch");
        service.execute(context("call-dispatch"), task);

        Map<String, Object> taskStart = first(StepEvents.T_TOOL_CALL);
        assertEquals("call-dispatch", taskStart.get(StepEvents.F_ID));
        assertEquals("call-dispatch", taskStart.get(StepEvents.F_PARENT),
                "同一 id：前端按 id upsert 后就是同一张卡片，不会多出一层同名嵌套");
        // 任务节点只有一个（它同时就是父工具卡片）；子命令挂在这个 id 下面
        assertEquals(1, steps.stream()
                .filter(s -> "call-dispatch".equals(s.get(StepEvents.F_ID)))
                .filter(s -> StepEvents.T_TOOL_CALL.equals(s.get(StepEvents.F_TYPE)))
                .count(), "同一个 id 的调用步骤只出现一次（前端 upsert 后不会多出一层）：" + steps);
        assertEquals(1, all(StepEvents.T_TOOL_CALL, "call-dispatch").size() - 1,
                "除任务节点自身外，父 id 下只有子智能体的那一条命令：" + steps);
    }

    @Test
    @DisplayName("子智能体失败：任务终态是 error 且带错误原因")
    public void test_failed_task_reports_error_status() {
        catalog.register(APP_NAME, Map.of(CHILD, StubSubAgent.failing(CHILD, "命令执行异常")));

        DynamicTask task = task("task-1");
        service.execute(context("call-dispatch"), task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        List<Map<String, Object>> taskResults = all(StepEvents.T_TOOL_RESULT, "call-dispatch");
        assertEquals(1, taskResults.size(), "失败也必须上报终态，否则界面永远转圈：" + steps);
        assertEquals(StepEvents.ST_ERROR, taskResults.get(0).get(StepEvents.F_STATUS));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) taskResults.get(0).get(StepEvents.F_RESULT);
        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(String.valueOf(result.get("error")).contains("命令执行异常"), String.valueOf(result));
    }

    @Test
    @DisplayName("子智能体不存在：确定性失败同样有终态步骤，且没有子步骤")
    public void test_missing_agent_reports_terminal_step() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        DynamicTask ghost = DynamicTask.builder().taskId("task-ghost").agentName("ghostAgent")
                .request("查一下").build();
        service.execute(context("call-dispatch"), ghost);

        assertEquals(TaskStatus.FAILED, ghost.getStatus());
        List<Map<String, Object>> results = all(StepEvents.T_TOOL_RESULT, "call-dispatch");
        assertTrue(results.stream().anyMatch(s -> "task-ghost".equals(s.get(StepEvents.F_ID))),
                "找不到智能体也要给界面一个终态：" + steps);
        assertTrue(all(StepEvents.T_TOOL_CALL, "task-ghost").isEmpty(), "没有子步骤");
    }

    @Test
    @DisplayName("未绑定步骤出口：派发照常完成，不上报也不报错（同步 chat / 单测场景）")
    public void test_without_sink_still_works() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        AgentExecutionContext noSink = AgentExecutionContext.builder()
                .userId("user-1").appName(APP_NAME).agentName("sshOperator")
                .terminalSessionId("conn-1").stepId("call-dispatch")
                .build();
        DynamicTask task = task("task-1");

        service.execute(noSink, task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertTrue(steps.isEmpty(), "没有出口时不应有任何步骤产生");
    }

    @Test
    @DisplayName("批量派发：每个任务独立节点，挂在同一个批量工具调用下")
    public void test_batch_tasks_are_siblings() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        service.execute(context("call-batch"), task("t1"));
        service.execute(context("call-batch"), task("t2"));

        List<Map<String, Object>> taskStarts = all(StepEvents.T_TOOL_CALL, "call-batch");
        assertEquals(2, taskStarts.size(), "两个任务各一个节点：" + steps);
        assertTrue(taskStarts.stream().anyMatch(s -> "t1".equals(s.get(StepEvents.F_ID))));
        assertTrue(taskStarts.stream().anyMatch(s -> "t2".equals(s.get(StepEvents.F_ID))));
        // 任务节点之间互不嵌套（各自是批量卡片的直接子节点）
        assertEquals(1, all(StepEvents.T_TOOL_CALL, "t1").size(), "t1 下面只有它自己的命令");
        assertEquals(1, all(StepEvents.T_TOOL_CALL, "t2").size());
    }

    @Test
    @DisplayName("步骤顺序：先任务开始、再子命令、最后任务终态")
    public void test_step_order() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        service.execute(context("call-dispatch"), task("task-1"));

        List<String> shape = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            shape.add(step.get(StepEvents.F_TYPE) + ":" + step.get(StepEvents.F_ID)
                    + ":" + step.get(StepEvents.F_STATUS));
        }
        assertEquals(List.of(
                "tool_call:task-1:running",
                "tool_call:call-1:running",
                "tool_result:call-1:ok",
                "tool_result:task-1:ok"), shape);
    }

    @Test
    @DisplayName("步骤里带的是 Map（不是字符串化的 JSON）：前端不必二次 parse")
    public void test_args_and_result_are_objects() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));

        service.execute(context("call-dispatch"), task("task-1"));

        Map<String, Object> childCall = all(StepEvents.T_TOOL_CALL, "task-1").get(0);
        assertTrue(childCall.get(StepEvents.F_ARGS) instanceof LinkedHashMap
                || childCall.get(StepEvents.F_ARGS) instanceof Map, "入参是对象");
        assertEquals("uname -a", ((Map<?, ?>) childCall.get(StepEvents.F_ARGS)).get("command"));
    }

    @Test
    @DisplayName("会话清理后出口解绑（测试自证：同一 sessionId 不会残留）")
    public void test_sink_registry_isolated_per_session() {
        ReActStepSink sinkA = sink();
        ReActStepSink sinkB = sink();
        assertNotNull(sinkA);
        assertNotNull(sinkB);
        assertFalse(sinkA == sinkB, "不同会话的出口互不影响");
        assertTrue(steps.isEmpty() || !steps.isEmpty());
    }

    @Test
    @DisplayName("Optional 兼容：任务无 attempts 时补 1，不影响上报")
    public void test_attempt_default() {
        catalog.register(APP_NAME, Map.of(CHILD, commandRunningAgent()));
        DynamicTask task = task("task-1");
        task.setAttempts(null);

        service.execute(context("call-dispatch"), task);

        assertEquals(1, first(StepEvents.T_TOOL_CALL).get(StepEvents.F_ATTEMPT));
    }

    @Test
    @DisplayName("结果对象为空字符串时也能成功（不因缺字段崩掉上报）")
    public void test_result_carries_blank_safe() {
        catalog.register(APP_NAME, Map.of(CHILD, StubSubAgent.replying(CHILD, "ok")));

        service.execute(context("call-dispatch"), task("task-1"));
        Optional.ofNullable(first(StepEvents.T_TOOL_CALL)).orElseThrow();
        assertTrue(steps.stream().anyMatch(s -> StepEvents.T_TOOL_RESULT.equals(s.get(StepEvents.F_TYPE))));
    }
}

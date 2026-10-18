package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.support.DispatchBudget;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批量派发工具（工具名 {@code dispatchSubAgents}）——父智能体一次调用提交一批任务，
 * 由编排器按依赖关系并发执行（教程 2-10）。
 *
 * <p>与 {@link DynamicPlanDispatchTool} 的分工：
 * <pre>
 *   dispatchSubAgents            父智能体<b>自己已经想清楚</b>怎么拆 → 直接提交任务数组
 *   planAndDispatchSubAgents     父智能体只想说"这任务复杂" → 交给规划器拆
 * </pre>
 *
 * <p>入参结构（LLM 可见）：
 * <pre>
 *   tasks           [{taskId?, agentName*, request*, dependsOn?, timeoutSeconds?}] 必填
 *   maxConcurrency  并发上限（默认 4，校验层钳制 [1,8]）
 *   failFast        失败即中止（默认 false；变更链路建议 true）
 * </pre>
 *
 * <p>为什么 schema 里没有 {@code maxRetries}：重试对非幂等写操作（改配置、重启服务）会放大风险，
 * 交给模型逐任务决定是错误的分工。内部仍保留重试能力（{@link SubAgentDispatchService} 的默认值 +
 * 配置层约束），但不再暴露给模型选择。
 *
 * <p>安全边界：{@code agentName} 必须是<b>父智能体声明的子智能体</b>（白名单来自 sub-agents 配置），
 * 模型无法派发配置之外的智能体；任务计划先过 {@link PlanParser} 与 {@link PlanValidator} 两道校验。
 */
@Slf4j
public class BatchSubAgentDispatchTool extends BaseTool {

    /** 单次批量派发的任务数上限 */
    private static final int MAX_TASKS = 10;

    /** 允许派发的子智能体白名单（来自父智能体的 sub-agents 声明） */
    private final List<String> allowedAgents;
    private final DynamicAgentOrchestrator orchestrator;
    private final PlanParser planParser;
    private final PlanValidator planValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchSubAgentDispatchTool(List<String> allowedAgents,
                                     DynamicAgentOrchestrator orchestrator,
                                     PlanParser planParser,
                                     PlanValidator planValidator) {
        super("dispatchSubAgents", "批量派发多个已配置子Agent：只在一轮里存在 2 个以上相互独立、"
                + "且每个都需要多轮排查/隔离上下文的任务时才用；单条命令或少量检查请自己 executeCommand。"
                + "支持任务依赖、并行执行与结果汇总");
        this.allowedAgents = List.copyOf(allowedAgents);
        this.orchestrator = orchestrator;
        this.planParser = planParser;
        this.planValidator = planValidator;
    }

    /** 函数签名：tasks 数组（每项含 agentName+request 必填，可选依赖/超时）+ 可选并发与 failFast */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        Schema taskSchema = Schema.builder()
                .type(new Type(Type.Known.OBJECT))
                .properties(ImmutableMap.of(
                        "taskId", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                        "agentName", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                        "request", Schema.builder().type(new Type(Type.Known.STRING)).build(),
                        "dependsOn", Schema.builder()
                                .type(new Type(Type.Known.ARRAY))
                                .items(Schema.builder().type(new Type(Type.Known.STRING)).build())
                                .build(),
                        "timeoutSeconds", Schema.builder().type(new Type(Type.Known.INTEGER)).build()))
                .required(ImmutableList.of("agentName", "request"))
                .build();

        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(ImmutableMap.of(
                                "tasks", Schema.builder()
                                        .type(new Type(Type.Known.ARRAY))
                                        .items(taskSchema)
                                        .build(),
                                "maxConcurrency", Schema.builder().type(new Type(Type.Known.INTEGER)).build(),
                                "failFast", Schema.builder().type(new Type(Type.Known.BOOLEAN)).build()))
                        .required(ImmutableList.of("tasks"))
                        .build())
                .build());
    }

    /**
     * 执行：参数 → 计划（与规划派发共用同一解析/校验路径）→ 编排执行 → 汇总返回。
     * <p>任何异常都收敛为 {@code {success:false, error}}，父智能体可据此修正计划重试。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String json = objectMapper.writeValueAsString(args);
            DynamicTaskPlan plan = planParser.parse(json, allowedAgents);
            planValidator.validate(plan, MAX_TASKS);

            AgentExecutionContext context = SubAgentDispatchContext.from(toolContext);
            String sessionId = SubAgentDispatchContext.sessionIdOf(toolContext);

            // 派发预算：先拦住"子智能体再派发"和"一轮派发太多次"，再逐任务占用额度
            String nested = DispatchBudget.rejectChildSessionDispatch(sessionId);
            if (nested != null) {
                log.warn("批量派发被拦截（嵌套派发）| parent:{} | sessionId:{}", context.getAgentName(), sessionId);
                return ImmutableMap.<String, Object>of("success", false, "error", nested);
            }
            String callError = DispatchBudget.reserveDispatchCall(sessionId);
            if (callError != null) {
                log.warn("批量派发被拦截（次数上限）| parent:{} | sessionId:{}", context.getAgentName(), sessionId);
                return ImmutableMap.<String, Object>of("success", false, "error", callError);
            }

            List<DynamicTask> accepted = new ArrayList<>();
            List<Map<String, Object>> rejected = new ArrayList<>();
            StringBuilder rejectSummary = new StringBuilder();
            for (DynamicTask task : plan.getTasks()) {
                String reason = DispatchBudget.reserveTask(sessionId, task.getAgentName(), task.getRequest());
                if (reason == null) {
                    accepted.add(task);
                } else {
                    rejected.add(ImmutableMap.of(
                            "taskId", task.getTaskId() == null ? "" : task.getTaskId(),
                            "agentName", task.getAgentName() == null ? "" : task.getAgentName(),
                            "reason", reason));
                    rejectSummary.append(task.getAgentName()).append(':').append(reason).append("；");
                }
            }
            if (accepted.isEmpty()) {
                log.warn("批量派发全部被拦截 | parent:{} | rejected:{}", context.getAgentName(), rejected.size());
                return ImmutableMap.<String, Object>of("success", false, "error",
                        "所有子任务都被派发预算拦截：" + rejectSummary
                                + "。请自己用 executeCommand 等工具完成，不要继续派发。");
            }
            if (!rejected.isEmpty()) {
                plan.setTasks(accepted);
            }

            log.info("批量派发开始 | parent:{} | tasks:{} | rejected:{} | maxConcurrency:{} | failFast:{} | terminalSession:{}",
                    context.getAgentName(), accepted.size(), rejected.size(), plan.getMaxConcurrency(), plan.getFailFast(),
                    context.getTerminalSessionId());

            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));
            if (!rejected.isEmpty()) {
                result.put("budgetRejected", rejected);
            }
            return result;
        }).onErrorReturn(error -> {
            log.warn("批量派发失败: {}", error.getMessage());
            return ImmutableMap.of("success", false, "error", String.valueOf(error.getMessage()));
        });
    }
}

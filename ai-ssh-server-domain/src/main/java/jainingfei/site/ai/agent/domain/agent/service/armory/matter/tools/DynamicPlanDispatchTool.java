package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.support.DispatchBudget;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态规划派发工具（工具名 {@code planAndDispatchSubAgents}）——连"怎么拆任务"都不用父智能体操心（教程 2-10）。
 *
 * <p>四步流水线：
 * <pre>
 *   ① 规划  PlannerAgentBuilder：临时规划器 LLM 输出 JSON 计划（60s 超时，跑完即弃）
 *   ② 解析  PlanParser：抢救 JSON、补全 taskId、白名单与字段校验
 *   ③ 校验  PlanValidator：数量上限、ID 去重、依赖存在、环检测、重试/并发钳制
 *   ④ 执行  DynamicAgentOrchestrator：按 DAG 并发调度，逐任务回写状态
 * </pre>
 *
 * <p>入参只有一个 {@code request}——父智能体只需判断"这任务我一个人拆不干净"，把原始诉求转交出去。
 * 失败（含规划器超时、计划非法）一律收敛为 {@code {success:false, error}}，父智能体可退回
 * 点名派发或自己处理。
 */
@Slf4j
public class DynamicPlanDispatchTool extends BaseTool {

    /** 单次规划派发的任务数上限 */
    private static final int MAX_TASKS = 10;

    private final PlannerAgentBuilder plannerAgentBuilder;
    private final DynamicAgentOrchestrator orchestrator;
    private final PlanParser planParser;
    private final PlanValidator planValidator;
    private final AiAgentConfigTableVO.Module.ChatModel modelConfig;
    private final List<String> allowedAgents;

    public DynamicPlanDispatchTool(PlannerAgentBuilder plannerAgentBuilder,
                                   DynamicAgentOrchestrator orchestrator,
                                   PlanParser planParser,
                                   PlanValidator planValidator,
                                   AiAgentConfigTableVO.Module.ChatModel modelConfig,
                                   List<String> allowedAgents) {
        super("planAndDispatchSubAgents", "根据任务复杂度自动规划并派发多个子Agent："
                + "适合包含多个检查项或需要多角色协同的复杂任务");
        this.plannerAgentBuilder = plannerAgentBuilder;
        this.orchestrator = orchestrator;
        this.planParser = planParser;
        this.planValidator = planValidator;
        this.modelConfig = modelConfig;
        this.allowedAgents = List.copyOf(allowedAgents);
    }

    /** 函数签名：入参仅一个必填字符串 request（用户原始任务描述） */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(ImmutableMap.of(
                                "request", Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("完整的用户任务描述，规划器会据此拆解子任务")
                                        .build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 执行：规划 → 解析 → 校验 → 编排。
     * <p>与批量派发共用同一条解析/校验/执行底座（"多种入口，一条底座"）。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        String request = String.valueOf(args.getOrDefault("request", ""));
        return Single.fromCallable(() -> {
            String sessionId = SubAgentDispatchContext.sessionIdOf(toolContext);

            // 派发预算前置：先拦嵌套与次数上限，避免白跑一次规划器 LLM
            String nested = DispatchBudget.rejectChildSessionDispatch(sessionId);
            if (nested != null) {
                log.warn("规划派发被拦截（嵌套派发）| sessionId:{}", sessionId);
                return ImmutableMap.<String, Object>of("success", false, "error", nested);
            }
            String callError = DispatchBudget.reserveDispatchCall(sessionId);
            if (callError != null) {
                log.warn("规划派发被拦截（次数上限）| sessionId:{}", sessionId);
                return ImmutableMap.<String, Object>of("success", false, "error", callError);
            }

            // ① 规划
            String planJson = plannerAgentBuilder.plan(modelConfig, request, allowedAgents);
            // ② 解析
            DynamicTaskPlan plan = planParser.parse(planJson, allowedAgents);
            // ③ 校验
            planValidator.validate(plan, MAX_TASKS);

            // 规划结果也要过预算：超限就整单拒绝，让父智能体缩小范围，不要盲目执行
            StringBuilder rejected = new StringBuilder();
            for (DynamicTask task : plan.getTasks()) {
                String reason = DispatchBudget.reserveTask(sessionId, task.getAgentName(), task.getRequest());
                if (reason != null) {
                    rejected.append(task.getAgentName()).append(':').append(reason).append("；");
                }
            }
            if (rejected.length() > 0) {
                log.warn("规划派发被预算拦截 | sessionId:{} | tasks:{} | reason:{}",
                        sessionId, plan.getTasks().size(), rejected);
                return ImmutableMap.<String, Object>of("success", false, "error",
                        "规划出的子任务超出本轮派发预算：" + rejected
                                + "请缩小范围后自己执行，或把剩余检查合并成少量命令。");
            }

            AgentExecutionContext context = SubAgentDispatchContext.from(toolContext);
            log.info("规划派发开始 | parent:{} | tasks:{} | maxConcurrency:{} | failFast:{} | terminalSession:{}",
                    context.getAgentName(), plan.getTasks().size(), plan.getMaxConcurrency(), plan.getFailFast(),
                    context.getTerminalSessionId());

            // ④ 编排执行
            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));
            return result;
        }).onErrorReturn(error -> {
            log.warn("动态规划派发失败: {}", error.getMessage());
            return ImmutableMap.of("success", false, "error", String.valueOf(error.getMessage()));
        });
    }
}

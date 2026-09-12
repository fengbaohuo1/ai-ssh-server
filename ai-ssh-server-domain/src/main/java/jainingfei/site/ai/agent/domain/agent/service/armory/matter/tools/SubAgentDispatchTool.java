package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.BaseAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 单任务子智能体派发工具——把一个子智能体包装成 ADK 工具，供父智能体按名调用（教程 2-10）。
 *
 * <p>工具名与描述直接取子智能体的 {@code name()/description()}：模型看到的不是"派发函数"，
 * 而是"一位专家"——这正是 Agent-as-Tool 的直观之处。
 *
 * <p><b>一条底座</b>：本工具不含任何执行细节，只是把一次派发翻译成
 * {@link DynamicTask} 交给 {@link SubAgentDispatchService}，与批量/规划派发<b>共用同一条执行与重试链路</b>
 * （教程里单派发与批量执行是两套实现，结果收敛与重试语义都不一致）。
 *
 * <p>入参只有一个 {@code request} 字符串：职责边界在于"派给谁"，而不在工具层拆解参数——
 * 子智能体自己有人格、工具与执行规则，父智能体只需要说清"要办什么事"。
 */
@Slf4j
public class SubAgentDispatchTool extends BaseTool {

    /** 被包装的子智能体 */
    private final BaseAgent subAgent;

    /** 统一执行底座（查询/会话/超时/重试/结果收敛） */
    private final SubAgentDispatchService dispatchService;

    public SubAgentDispatchTool(BaseAgent subAgent, SubAgentDispatchService dispatchService) {
        super(subAgent.name(), subAgent.description());
        this.subAgent = subAgent;
        this.dispatchService = dispatchService;
    }

    /** 工具声明：入参仅一个必填字符串 request（下发给子智能体的完整任务指令） */
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
                                        .description("下发给该子智能体的完整任务指令，一句话说清目标与期望产出")
                                        .build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 执行派发：构建执行上下文（含父会话绑定的 SSH 终端会话）→ 交给执行底座 →
     * 收敛为 {@code {success, result}} 或 {@code {success:false, error}}。
     * <p>任何异常都不外抛：父智能体能拿到失败原因并自行换思路，而不是整轮对话被打断。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        String request = String.valueOf(args.getOrDefault("request", ""));
        return Single.<Map<String, Object>>fromCallable(() -> {
            AgentExecutionContext context = SubAgentDispatchContext.from(toolContext);

            // taskId 取本次工具调用 id：让"派发卡片"与"任务节点"共用同一个步骤 id，
            // 前端按 id upsert 后原地升级（子智能体的命令挂在它下面），不再多一层同名嵌套
            String stepId = context.getStepId();
            DynamicTask task = DynamicTask.builder()
                    .taskId(stepId != null && !stepId.isBlank() ? stepId : "dispatch-" + UUID.randomUUID())
                    .agentName(subAgent.name())
                    .request(request)
                    .build();

            log.info("子Agent派发开始 | parent:{} | subAgent:{} | appName:{} | terminalSession:{}",
                    context.getAgentName(), subAgent.name(), context.getAppName(), context.getTerminalSessionId());

            dispatchService.execute(context, task);

            if (task.getStatus() == TaskStatus.COMPLETED) {
                log.info("子Agent派发完成 | subAgent:{} | resultLength:{}",
                        subAgent.name(), task.getResult() == null ? 0 : task.getResult().length());
                return ImmutableMap.<String, Object>of("success", true, "result", task.getResult());
            }
            log.warn("子Agent派发失败 | subAgent:{} | error:{}", subAgent.name(), task.getError());
            return ImmutableMap.<String, Object>of("success", false, "error",
                    task.getError() == null ? "unknown error" : task.getError());
        }).onErrorReturn(error -> {
            log.error("子Agent派发异常 | subAgent:{} | request:{}", subAgent.name(), request, error);
            return ImmutableMap.of("success", false, "error", String.valueOf(error.getMessage()));
        });
    }

    /** 被包装的子智能体名（观测/测试用） */
    public String subAgentName() {
        return subAgent.name();
    }
}

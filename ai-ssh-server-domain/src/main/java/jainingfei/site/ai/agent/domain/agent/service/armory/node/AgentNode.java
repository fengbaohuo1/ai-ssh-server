package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiLlmAdapter;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.BatchSubAgentDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.DynamicAgentOrchestrator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.DynamicPlanDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanParser;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanValidator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlannerAgentBuilder;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体装配节点——<b>二段式装配</b>（教程 2-10）。
 *
 * <p>第一段：按配置把每个智能体建成 {@link LlmAgent}，统一挂上"SSH 命令 + MCP/技能"工具集。
 * <p>第二段 {@link #buildAgentTools}：对<b>声明了 {@code sub-agents} 的智能体</b>，
 * 用同名智能体重建并覆盖 {@code agentGroup}——它的工具换成派发工具集（每个子智能体一个
 * {@link SubAgentDispatchTool} + {@link BatchSubAgentDispatchTool} + {@link DynamicPlanDispatchTool}），
 * <b>不再持有 executeCommand</b>：父智能体成为"只拆解、不执行"的编排者，
 * 命令只能由子智能体执行，权责边界由架构保证而不是靠提示词自觉。
 *
 * <p>两个实现细节：
 * <ul>
 *   <li>{@code LlmAgent} 构建完成后工具不可变（ADK Builder 约束），所以只能"用相同 name 重建"再覆盖</li>
 *   <li>第二段在所有智能体都进 {@code agentGroup} 之后执行，因此与 YAML 里 agents 的书写顺序无关；
 *       子智能体名写错则在<b>装配期</b>直接抛异常（fail-fast），而不是等到运行期才发现</li>
 * </ul>
 *
 * <p>MCP/技能工具集保持对所有智能体可见（父智能体失去的只是 SSH 执行能力）。
 */
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private SubAgentDispatchService subAgentDispatchService;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;

    @Resource
    private PlanParser planParser;

    @Resource
    private PlanValidator planValidator;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        ChatModel chatModel = dynamicContext.getChatModel();

        // MCP/技能工具集（ChatModelNode 装配）：不再向共享列表里塞 SSH 工具，
        // 保证父智能体重建时可以只拿到"非执行类"工具
        List<BaseToolset> mcpOnlyToolsets = new ArrayList<>(dynamicContext.getMcpToolsets());
        List<BaseToolset> sshCapableToolsets = new ArrayList<>(mcpOnlyToolsets);
        sshCapableToolsets.add(new StaticToolset(List.of(sshExecuteAdkTool)));

        // 第一段：全员装配
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            dynamicContext.getAgentGroup().put(agentConfig.getName(),
                    buildAgent(agentConfig, chatModel, sshCapableToolsets));
        }

        // 第二段：为声明了 sub-agents 的智能体换上派发工具集
        buildAgentTools(dynamicContext, agents, chatModel, mcpOnlyToolsets,
                aiAgentConfigTableVO.getModule().getChatModel());

        return router(requestParameter, dynamicContext);
    }

    /** 按配置构建 LlmAgent（V3 托管循环：FixedSpringAI 输出原生 FunctionCall part，Runner 内部循环推进） */
    private LlmAgent buildAgent(AiAgentConfigTableVO.Module.Agent agentConfig,
                                ChatModel chatModel,
                                List<?> tools) {
        return LlmAgent.builder()
                .name(agentConfig.getName())
                .instruction(agentConfig.getInstruction())
                .description(agentConfig.getDescription())
                .model(new SpringAiLlmAdapter(chatModel))
                .outputKey(agentConfig.getOutputKey())
                .tools(tools)
                .build();
    }

    /**
     * 第二段装配：父智能体只持有派发工具 + MCP/技能工具。
     *
     * @param mcpOnlyToolsets 不含 SSH 执行工具的工具集（父智能体保留 MCP/技能能力）
     */
    private void buildAgentTools(DefaultArmoryFactory.DynamicContext dynamicContext,
                                 List<AiAgentConfigTableVO.Module.Agent> agents,
                                 ChatModel chatModel,
                                 List<BaseToolset> mcpOnlyToolsets,
                                 AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            List<String> subAgentNames = agentConfig.getSubAgents();
            if (subAgentNames == null || subAgentNames.isEmpty()) {
                continue;   // 未声明子智能体：保持第一段的装配结果（行为与旧版一致）
            }

            List<BaseTool> dispatchTools = new ArrayList<>();
            for (String subAgentName : subAgentNames) {
                BaseAgent subAgent = dynamicContext.getAgentGroup().get(subAgentName);
                if (subAgent == null) {
                    throw new IllegalArgumentException("sub agent not found: " + subAgentName
                            + "（父智能体 " + agentConfig.getName() + " 的 sub-agents 声明了未装配的智能体名）");
                }
                dispatchTools.add(new SubAgentDispatchTool(subAgent, subAgentDispatchService));
            }
            dispatchTools.add(new BatchSubAgentDispatchTool(
                    subAgentNames, dynamicAgentOrchestrator, planParser, planValidator));
            dispatchTools.add(new DynamicPlanDispatchTool(
                    plannerAgentBuilder, dynamicAgentOrchestrator, planParser, planValidator,
                    chatModelConfig, subAgentNames));

            List<Object> tools = new ArrayList<>();
            tools.add(new StaticToolset(dispatchTools));
            tools.addAll(mcpOnlyToolsets);

            dynamicContext.getAgentGroup().put(agentConfig.getName(),
                    buildAgent(agentConfig, chatModel, tools));

            log.info("子Agent装配完成 | parent:{} | subAgents:[{}] | tools:[{}] | 父智能体不再持有 executeCommand",
                    agentConfig.getName(), String.join(",", subAgentNames),
                    dispatchTools.stream().map(BaseTool::name).collect(Collectors.joining(", ")));
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }
}

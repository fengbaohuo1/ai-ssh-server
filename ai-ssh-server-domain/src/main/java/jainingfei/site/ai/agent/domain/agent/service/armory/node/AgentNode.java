package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.adk.FixedSpringAI;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {
    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();
        List<BaseToolset> mcpToolsets = dynamicContext.getMcpToolsets();
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        // 注册 ADK 原生工具：SSH 命令执行（SshExecuteAdkTool extends BaseTool）
        mcpToolsets.add(new StaticToolset(List.of(sshExecuteAdkTool)));

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .instruction(agentConfig.getInstruction())
                    .description(agentConfig.getDescription())
                    // V3 托管循环：FixedSpringAI 输出原生 FunctionCall part，ADK Runner 内部循环
                    // 自动执行工具并推进到最终回复（finalResponse）；循环熔断走 RunConfig.maxLlmCalls
                    .model(new FixedSpringAI(chatModel))
                    .outputKey(agentConfig.getOutputKey())
                    .tools(mcpToolsets)
                    .build();
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }
}

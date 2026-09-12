package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.factory.McpCreateFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.model.ChatModelBundle;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.model.ChatModelFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 大模型 + 工具装配节点。
 * <p>
 * <b>架构</b>：MCP 三类工厂（remote/stdio/local）产出 {@link BaseToolset} → ADK。
 * Spring AI 只负责通信，ADK 负责工具循环 + 编排。
 * <p>
 * <b>厂商边界</b>：本节点不出现任何厂商类型，模型一律经 {@link ChatModelFactory} 取得
 * {@link ChatModelBundle}（主链路 = thinking 跟随默认 + yml reasoning-effort）。
 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource private AgentNode agentNode;
    @Resource private McpCreateFactory mcpCreateFactory;
    @Resource private ToolSkillsCreateService toolSkillsCreateService;
    @Resource private ChatModelFactory chatModelFactory;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        // 1. MCP 三类工厂 → BaseToolset
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        if (ObjectUtils.isNotEmpty(toolMcpList)) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                BaseToolset toolset = mcpCreateFactory.create(toolMcp);
                dynamicContext.getMcpToolsets().add(toolset);
                String name = toolMcp.getRemote() != null ? toolMcp.getRemote().getName()
                        : toolMcp.getStdio() != null ? toolMcp.getStdio().getName() : toolMcp.getLocal().getName();
                log.info("MCP 工具 [{}] → BaseToolset", name);
            }
        }

        // 2. Skills → instruction + BaseToolset
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();
        if (ObjectUtils.isNotEmpty(toolSkillsList)) {
            String skillsInstruction = toolSkillsCreateService.getSkillsInstruction(toolSkillsList);
            if (skillsInstruction != null && !skillsInstruction.isEmpty()) {
                for (AiAgentConfigTableVO.Module.Agent agentConfig : aiAgentConfigTableVO.getModule().getAgents())
                    agentConfig.setInstruction(agentConfig.getInstruction() + skillsInstruction);
                log.info("Skills 指令已注入, length:{}", skillsInstruction.length());
            }
            dynamicContext.getMcpToolsets().add(toolSkillsCreateService.createToolset(toolSkillsList));
            log.info("Skills → BaseToolset");
        }

        // 3. ChatModel：唯一入口 ChatModelFactory（厂商类型只存在于 bundle 实现内部）
        ChatModelBundle chatModelBundle = chatModelFactory.createMain(chatModelConfig);
        dynamicContext.setChatModelBundle(chatModelBundle);
        dynamicContext.setChatModel(chatModelBundle.chatModel());

        log.info("ChatModelNode 装配完成（provider:{}, model:{}, reasoningEffort:{}）, mcpToolsets:{}",
                chatModelBundle.providerName(), chatModelConfig.getModel(), chatModelConfig.getReasoningEffort(),
                dynamicContext.getMcpToolsets().size());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }
}

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
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 大模型 + 工具装配节点。
 * <p>
 * <b>架构</b>：MCP 三类工厂（remote/stdio/local）产出 {@link BaseToolset} → ADK。
 * Spring AI 只负责通信，ADK 负责工具循环 + 编排。
 * <p>
 * V2.1: 升级 ADK 1.6.0，移除 OpenAiChatModelAdapter + AgentStepBuffer。
 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource private AgentNode agentNode;
    @Resource private McpCreateFactory mcpCreateFactory;
    @Resource private ToolSkillsCreateService toolSkillsCreateService;

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

        // 3. ChatModel
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .baseUrl(chatModelConfig.getBaseUrl()).apiKey(chatModelConfig.getApiKey())
                .model(chatModelConfig.getModel()).build();
        dynamicContext.setChatModel(new ChatModelFix(OpenAiChatModel.builder().options(chatOptions).build()));

        log.info("ChatModelNode 装配完成, mcpToolsets:{}", dynamicContext.getMcpToolsets().size());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

    // ==== 内部：修正 ADK MessageConverter 产出非 OpenAiChatOptions 的 ClassCastException ====

    private static class ChatModelFix implements ChatModel, StreamingChatModel {
        private final OpenAiChatModel delegate;
        private final OpenAiChatOptions defaultOptions;
        ChatModelFix(OpenAiChatModel d) { this.delegate = d; this.defaultOptions = (OpenAiChatOptions) d.getOptions(); }
        @Override public ChatResponse call(Prompt p) { return delegate.call(fix(p)); }
        @Override public Flux<ChatResponse> stream(Prompt p) { return delegate.stream(fix(p)); }
        private Prompt fix(Prompt p) {
            ChatOptions o = p.getOptions();
            if (o == null || o instanceof OpenAiChatOptions) return p;
            // ADK 产出 ToolCallingChatOptions → 转为 OpenAiChatOptions 保留工具声明
            OpenAiChatOptions opts = OpenAiChatOptions.builder()
                    .baseUrl(defaultOptions.getBaseUrl()).apiKey(defaultOptions.getApiKey())
                    .model(defaultOptions.getModel())
                    .toolCallbacks(((ToolCallingChatOptions) o).getToolCallbacks()).build();
            return new Prompt(p.getInstructions(), opts);
        }
    }
}

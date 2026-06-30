package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.OpenAiChatModelAdapter;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.factory.McpCreateFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型 + 工具装配节点。
 * <p>
 * 所有工具（MCP remote/stdio/local + Skills）统一转为 {@link ToolCallback}，
 * 通过 {@link ToolCallingManager} 注入 {@link OpenAiChatModel}。
 * ADK 不再管理任何工具，只负责 Agent 编排。
 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;
    @Resource
    private McpCreateFactory mcpCreateFactory;
    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;


    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        // 1. 收集所有 ToolCallback（MCP + Skills）
        List<ToolCallback> allToolCallbacks = new ArrayList<>();

        // 1a. MCP 工具（remote / stdio / local）→ ToolCallback[]
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        if (ObjectUtils.isNotEmpty(toolMcpList)) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                ToolCallback[] callbacks = mcpCreateFactory.create(toolMcp);
                for (ToolCallback cb : callbacks) {
                    allToolCallbacks.add(cb);
                }
                log.info("MCP 工具 [{}] → ToolCallback, count:{}",
                        toolMcp.getRemote() != null ? toolMcp.getRemote().getName() :
                        toolMcp.getStdio() != null ? toolMcp.getStdio().getName() :
                        toolMcp.getLocal().getName(), callbacks.length);
            }
        }

        // 1b. Skills → 注入 instruction + 收集 ToolCallback
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();
        if (ObjectUtils.isNotEmpty(toolSkillsList)) {
            String skillsInstruction = toolSkillsCreateService.getSkillsInstruction(toolSkillsList);
            if (skillsInstruction != null && !skillsInstruction.isEmpty()) {
                List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
                if (ObjectUtils.isNotEmpty(agents)) {
                    for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
                        agentConfig.setInstruction(agentConfig.getInstruction() + skillsInstruction);
                    }
                }
                log.info("Skills 指令已注入 agent instruction, length:{}", skillsInstruction.length());
            }
            allToolCallbacks.addAll(toolSkillsCreateService.getToolCallbacks(toolSkillsList));
        }

        // 2. 构建 ToolCallingManager（执行工具调用）
        ToolCallingManager toolCallingManager = null;
        if (!allToolCallbacks.isEmpty()) {
            StaticToolCallbackResolver toolResolver = new StaticToolCallbackResolver(allToolCallbacks);
            toolCallingManager = DefaultToolCallingManager.builder()
                    .observationRegistry(ObservationRegistry.NOOP)
                    .toolCallbackResolver(toolResolver)
                    .build();
            log.info("ToolCallingManager 已创建, toolCount:{}", allToolCallbacks.size());
        }

        // 3. OpenAiChatOptions（带 toolCallbacks，声明工具给 LLM）
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .model(chatModelConfig.getModel())
                .toolCallbacks(allToolCallbacks)
                .build();
        log.info("OpenAiChatOptions 已创建, toolCallbacks:{}", allToolCallbacks.size());

        // 3b. 无工具的 clean options（工具执行后第二轮使用，防止死循环）
        OpenAiChatOptions cleanOptions = OpenAiChatOptions.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .model(chatModelConfig.getModel())
                .build();

        // 4. 构建 OpenAiChatModel（不传 ToolCallingManager，由 adapter 管理工具循环）
        OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                .options(chatOptions)
                .build();

        OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(
                openAiChatModel, chatOptions, cleanOptions, toolCallingManager);
        dynamicContext.setChatModel(adapter);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }
}

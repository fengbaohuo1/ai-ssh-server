package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolCallbackBaseTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.factory.McpCreateFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型 + 工具装配节点。
 * <p>
 * 所有工具（MCP remote/stdio/local + Skills）统一转为 {@link ToolCallback}，
 * 通过 {@link OpenAiChatOptions#toolCallbacks()} 声明给 LLM，
 * 同时包装为 {@link BaseToolset} 注册到 Google ADK 的 {@code LlmAgent.tools()}，
 * 由 ADK 原生管理工具调用循环。
 * <p>
 * V2.1: 升级 Google ADK 1.6.0 后移除 {@code OpenAiChatModelAdapter}，
 * 工具执行改为由 ADK 的 {@code LlmAgent} 原生处理。
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

        // 2. 创建 BaseToolset（供 ADK 原生管理工具调用循环）
        if (!allToolCallbacks.isEmpty()) {
            List<BaseTool> baseTools = new ArrayList<>();
            for (ToolCallback tc : allToolCallbacks) {
                baseTools.add(new ToolCallbackBaseTool(tc));
            }
            BaseToolset toolset = new StaticToolset(baseTools);
            dynamicContext.getMcpToolsets().add(toolset);
            log.info("BaseToolset 已创建并注册到 ADK, toolCount:{}", baseTools.size());
        }

        // 3. OpenAiChatOptions（带 toolCallbacks，声明工具给 LLM）
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .model(chatModelConfig.getModel())
                .toolCallbacks(allToolCallbacks)
                .build();
        log.info("OpenAiChatOptions 已创建, toolCallbacks:{}", allToolCallbacks.size());

        // 4. 构建 OpenAiChatModel（直接传给 ADK SpringAI 适配器，
        //    工具声明在 OpenAiChatOptions 中，工具执行由 ADK LlmAgent 管理）
        OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                .options(chatOptions)
                .build();
        dynamicContext.setChatModel(openAiChatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }
}

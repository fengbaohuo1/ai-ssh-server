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
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;

/**
 * 大模型 + 工具装配节点。
 * <p>
 * <b>架构</b>：MCP 三类工厂（remote/stdio/local）产出 {@link BaseToolset} → ADK。
 * Spring AI 只负责通信，ADK 负责工具循环 + 编排。
 * <p>
 * V2.2: 主模型切换到 spring-ai-deepseek 2.0.1 原生供应商（DeepSeek thinking 模式官方支持，
 * reasoning_content 捕获/回传走 DeepSeekAssistantMessage，替代 OpenAI 兼容层）。
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

        // 3. ChatModel（DeepSeek 原生供应商：thinking 默认开启，reasoning_content 官方捕获/回传）
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .build();
        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                .model(resolveChatModel(chatModelConfig.getModel()));

        // 推理强度（仅推理模型生效；deepseek 枚举仅 HIGH/MAX，可配置时按需映射）
        String reasoningEffort = chatModelConfig.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            resolveReasoningEffort(reasoningEffort).ifPresent(optionsBuilder::reasoningEffort);
        }

        dynamicContext.setChatModel(new DeepSeekChatModelFix(
                DeepSeekChatModel.builder()
                        .deepSeekApi(deepSeekApi)
                        .options(optionsBuilder.build())
                        .build()));

        log.info("ChatModelNode 装配完成（DeepSeek 原生通道，thinking 开启）, mcpToolsets:{}",
                dynamicContext.getMcpToolsets().size());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

    /** yml 模型名 → DeepSeek 枚举（deepseek-v4-flash → DEEPSEEK_V4_FLASH），未知名回退默认并告警 */
    private static DeepSeekApi.ChatModel resolveChatModel(String model) {
        if (StringUtils.isNotBlank(model)) {
            try {
                return DeepSeekApi.ChatModel.valueOf(model.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                log.warn("未知 DeepSeek 模型名 [{}]，回退默认模型", model);
            }
        }
        return DeepSeekApi.ChatModel.DEEPSEEK_V4_FLASH;
    }

    /** 推理强度字符串 → DeepSeek 枚举（当前仅 HIGH/MAX），不识别时忽略 */
    private static java.util.Optional<DeepSeekApi.ChatCompletionRequest.ReasoningEffort> resolveReasoningEffort(String effort) {
        try {
            return java.util.Optional.of(DeepSeekApi.ChatCompletionRequest.ReasoningEffort
                    .valueOf(effort.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            log.warn("未知推理强度 [{}]，忽略（DeepSeek 支持: {}", effort,
                    java.util.Arrays.toString(DeepSeekApi.ChatCompletionRequest.ReasoningEffort.values()));
            return java.util.Optional.empty();
        }
    }

    // ==== 内部：修正 ADK MessageConverter 产出非 DeepSeekChatOptions 的 ClassCastException ====
    // ADK 的 Spring AI 转换层把 prompt options 交给 ChatModel 时类型不匹配，与 OpenAiChatModel
    // 时代同源；DeepSeekChatModel.createRequest 会强转 DeepSeekChatOptions，因此工具轮次的
    // ToolCallingChatOptions 必须以 defaultOptions 为基底合并为 DeepSeekChatOptions。

    private static class DeepSeekChatModelFix implements ChatModel {
        private final DeepSeekChatModel delegate;
        private final DeepSeekChatOptions defaultOptions;
        DeepSeekChatModelFix(DeepSeekChatModel d) { this.delegate = d; this.defaultOptions = (DeepSeekChatOptions) d.getOptions(); }
        @Override public ChatResponse call(Prompt p) { return delegate.call(fix(p)); }
        @Override public Flux<ChatResponse> stream(Prompt p) { return delegate.stream(fix(p)); }
        private Prompt fix(Prompt p) {
            ChatOptions o = p.getOptions();
            if (o == null || o instanceof DeepSeekChatOptions) return p;
            // ADK 产出 ToolCallingChatOptions → 转为 DeepSeekChatOptions：透传模型与工具声明
            ToolCallingChatOptions t = (ToolCallingChatOptions) o;
            DeepSeekChatOptions.Builder merged = DeepSeekChatOptions.builder()
                    .model(defaultOptions.getModel())
                    .toolCallbacks(t.getToolCallbacks());
            if (defaultOptions.getThinking() != null) {
                merged.thinking(defaultOptions.getThinking());
            }
            if (defaultOptions.getReasoningEffort() != null) {
                merged.reasoningEffort(defaultOptions.getReasoningEffort());
            }
            return new Prompt(p.getInstructions(), merged.build());
        }
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DeepSeek 原生通道的模型包——<b>全项目唯一允许出现 DeepSeek 类型的类</b>。
 *
 * <p>原先散落三处的厂商代码在这里收口：
 * <pre>
 *   ChatModelNode.DeepSeekChatModelFix   options 类型转换（已删除）
 *   ChatModelNode.resolveChatModel       模型名 → 枚举（含未知回退）
 *   ChatModelNode.resolveReasoningEffort 推理强度 → 枚举
 *   FixedSpringAI.captureReasoning       DeepSeekAssistantMessage 读草稿
 *   FixedSpringAI.toHistoryAssistantMessages  DeepSeekAssistantMessage 写历史
 *   FixedSpringAI.extractCachedTokens    DeepSeekApi.Usage 取缓存命中
 * </pre>
 *
 * <p>关键实现说明（对齐 Spring AI 2.0.1 源码行为）：
 * <ul>
 *   <li>{@code DeepSeekChatModel.buildRequestPrompt()} 只在 {@code prompt.getOptions() == null}
 *       时补默认 options；非空时原样使用，随后 {@code createRequest()} 强转
 *       {@code (DeepSeekChatOptions)}。所以 {@link #options(List)} 必须用
 *       {@code defaults.mutate()} 复制默认值再挂工具回调——这正是原 DeepSeekChatModelFix 做的事。</li>
 *   <li>{@code DeepSeekChatModel.createRequest()} 对 assistant 消息断言 {@code text != null}，
 *       故 {@link #assistantMessage} 把空可见文本兜底为空串（内容由 tool_calls 承载）。</li>
 *   <li>思考草稿走 {@code DeepSeekAssistantMessage.reasoningContent} 往返：
 *       {@code createRequest()} 内部用 {@code instanceof DeepSeekAssistantMessage} 读取。</li>
 * </ul>
 *
 * <p>案例（主链路与辅助链路共用本类，仅调参不同）：
 * <pre>
 *   createMain(cfg)         → thinking 跟随默认 + reasoningEffort=High  （聊天主模型）
 *   createAux(cfg, 0.1)     → 关思考 + temperature=0.1                （意图识别）
 *   createAux(cfg, null)    → 关思考                                  （规划器）
 * </pre>
 */
@Slf4j
public class DeepSeekChatModelBundle implements ChatModelBundle {

    private final DeepSeekChatModel chatModel;

    /** 默认 options（模型/思考/推理强度/温度）；工具轮次由 {@link #options(List)} 复制后使用 */
    private final DeepSeekChatOptions defaults;

    private final String modelName;

    private DeepSeekChatModelBundle(DeepSeekChatModel chatModel, DeepSeekChatOptions defaults, String modelName) {
        this.chatModel = chatModel;
        this.defaults = defaults;
        this.modelName = modelName;
    }

    /**
     * 装配 DeepSeek 原生通道模型。
     *
     * @param chatModelConfig 配置文件 chat-model（base-url / api-key / model）
     * @param tuning          厂商中立的调参（thinking / reasoningEffort / temperature）
     */
    public static DeepSeekChatModelBundle of(AiAgentConfigTableVO.Module.ChatModel chatModelConfig,
                                             ModelTuning tuning) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .build();

        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                .model(resolveChatModel(chatModelConfig.getModel()));

        if (tuning != null) {
            if (tuning.getThinking() != null) {
                if (tuning.getThinking()) {
                    optionsBuilder.enableThinking();
                } else {
                    optionsBuilder.disableThinking();
                }
            }
            if (tuning.getTemperature() != null) {
                optionsBuilder.temperature(tuning.getTemperature());
            }
            if (StringUtils.isNotBlank(tuning.getReasoningEffort())) {
                resolveReasoningEffort(tuning.getReasoningEffort()).ifPresent(optionsBuilder::reasoningEffort);
            }
        }

        DeepSeekChatOptions options = optionsBuilder.build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();

        log.info("DeepSeek 原生通道装配完成 | model:{} | thinking:{} | reasoningEffort:{} | temperature:{}",
                options.getModel(), options.getThinking(), options.getReasoningEffort(), options.getTemperature());
        return new DeepSeekChatModelBundle(chatModel, options, options.getModel());
    }

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    /** 模型名（日志用） */
    public String modelName() {
        return modelName;
    }

    @Override
    public ChatOptions options(List<ToolCallback> toolCallbacks) {
        // 复制默认 options（模型/思考/推理强度）再挂本轮工具回调：
        // provider 在 options 非空时不再合并默认值，漏带就会丢模型名与思考配置
        return defaults.mutate().toolCallbacks(toolCallbacks).build();
    }

    @Override
    public String reasoningContent(AssistantMessage message) {
        if (message instanceof DeepSeekAssistantMessage ds) {
            return ds.getReasoningContent();
        }
        return null;
    }

    @Override
    public AssistantMessage assistantMessage(String visibleText, String reasoning,
                                             List<AssistantMessage.ToolCall> toolCalls) {
        DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder();
        if (reasoning != null) {
            builder.reasoningContent(reasoning);
        }
        // DeepSeekChatModel.createRequest 断言 content 文本非 null
        builder.content(visibleText == null ? "" : visibleText);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    @Override
    public Long cachedTokens(Usage usage) {
        try {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof DeepSeekApi.Usage dsUsage) {
                DeepSeekApi.Usage.PromptTokensDetails details = dsUsage.promptTokensDetails();
                return details != null ? details.cachedTokens().longValue() : null;
            }
        } catch (Exception e) {
            log.debug("DeepSeek 缓存命中 token 提取失败: {}", e.getMessage());
        }
        return null;
    }

    // ===== 模型名 / 推理强度 → DeepSeek 枚举（原 ChatModelNode 逻辑，行为不变） =====

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

    /** 推理强度字符串 → DeepSeek 枚举（当前仅 HIGH/MAX），不识别时忽略并告警 */
    private static Optional<DeepSeekApi.ChatCompletionRequest.ReasoningEffort> resolveReasoningEffort(String effort) {
        try {
            return Optional.of(DeepSeekApi.ChatCompletionRequest.ReasoningEffort
                    .valueOf(effort.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            log.warn("未知推理强度 [{}]，忽略（DeepSeek 支持: {}）", effort,
                    java.util.Arrays.toString(DeepSeekApi.ChatCompletionRequest.ReasoningEffort.values()));
            return Optional.empty();
        }
    }
}

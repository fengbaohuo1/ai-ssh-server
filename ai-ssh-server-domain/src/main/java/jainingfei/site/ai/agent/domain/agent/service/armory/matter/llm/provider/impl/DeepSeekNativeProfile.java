package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.impl;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.CacheMetricsAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ModelTuning;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DeepSeek 原生通道档案——<b>全项目唯一允许出现 DeepSeek 类型的类</b>。
 *
 * <p>为什么要留这条通道：同一家的原生 SDK 比协议入口多两样东西，且都是本项目用得上的：
 * <pre>
 *   ① 流式也能拿到推理内容（DeepSeekAssistantMessage.reasoningContent 往返）
 *      —— 协议通道在 spring-ai-openai 的 chunk 合并里丢掉了该字段（上游缺口，见 ProviderCapabilities）
 *   ② 原生 usage 带缓存命中明细（DeepSeekApi.Usage.PromptTokensDetails.cachedTokens）
 *      —— 通用 Usage.getCacheReadInputTokens() 在本通道未被填充
 * </pre>
 * 两条能力都由本类内聚实现，上层（适配器/装配/派发/编排）依旧只认 {@link ProviderProfile}。
 *
 * <p>关键实现约束（对齐 spring-ai-deepseek 2.0.1 源码行为）：
 * <ul>
 *   <li>{@code DeepSeekChatModel} 在 {@code prompt.getOptions()} 非空时<b>不再合并默认 options</b>，
 *       因此工具轮次的 options 必须由 {@code getOptions().mutate()} 复制（见
 *       {@code SpringAiPromptAssembler#buildToolOptions}，与 ADK 官方适配器同一套写法）</li>
 *   <li>{@code createRequest()} 对 assistant 消息断言 text 非 null → 历史消息的空可见文本兜底为空串</li>
 *   <li>模型名<b>原样透传</b>（不做枚举映射、不做静默回退）：配置写错就让服务端报错，
 *       而不是悄悄换成另一个模型</li>
 * </ul>
 */
@Slf4j
public class DeepSeekNativeProfile implements ProviderProfile {

    private static final String ID = ProviderCapabilities.DEEPSEEK_NATIVE;

    private final DeepSeekChatModel chatModel;
    private final ProviderCapabilities capabilities;

    private DeepSeekNativeProfile(DeepSeekChatModel chatModel, ProviderCapabilities capabilities) {
        this.chatModel = chatModel;
        this.capabilities = capabilities;
    }

    /**
     * 装配 DeepSeek 原生通道模型。
     *
     * @param config 配置文件 chat-model（base-url / api-key / model）
     * @param tuning 厂商中立调参（thinking / reasoningEffort / temperature）
     */
    public static DeepSeekNativeProfile of(AiAgentConfigTableVO.Module.ChatModel config, ModelTuning tuning) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .build();

        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder()
                // 模型名原样透传：本通道不做"模型名 → 枚举"映射，避免未知名字被静默替换
                .model(config.getModel());

        String thinking = "默认";
        if (tuning != null) {
            if (tuning.getThinking() != null) {
                if (tuning.getThinking()) {
                    options.enableThinking();
                    thinking = "开启";
                } else {
                    options.disableThinking();
                    thinking = "关闭";
                }
            }
            if (tuning.getTemperature() != null) {
                options.temperature(tuning.getTemperature());
                // 源码事实：思考模式下 temperature 被服务端静默忽略（只记 WARN，参数照发不生效）
                if (tuning.getThinking() == null) {
                    log.warn("本通道思考模式（默认开启）会静默忽略 temperature={}；"
                            + "辅助链路请用 ModelTuning.deterministic(...)（显式关思考）", tuning.getTemperature());
                }
            }
            if (StringUtils.isNotBlank(tuning.getReasoningEffort())) {
                Optional<DeepSeekApi.ChatCompletionRequest.ReasoningEffort> effort =
                        resolveReasoningEffort(tuning.getReasoningEffort());
                effort.ifPresent(options::reasoningEffort);
            }
        }

        DeepSeekChatOptions built = options.build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(built)
                .build();

        log.info("DeepSeek 原生通道装配完成 | model:{} | thinking:{} | reasoningEffort:{} | temperature:{}",
                built.getModel(), thinking, built.getReasoningEffort(),
                built.getTemperature() == null ? "默认" : built.getTemperature());

        return new DeepSeekNativeProfile(chatModel, ProviderCapabilities.forProvider(ID));
    }

    /** 推理强度字符串 → 原生枚举（当前仅 HIGH/MAX）；不识别时忽略并告警 */
    private static Optional<DeepSeekApi.ChatCompletionRequest.ReasoningEffort> resolveReasoningEffort(String effort) {
        try {
            return Optional.of(DeepSeekApi.ChatCompletionRequest.ReasoningEffort
                    .valueOf(effort.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            log.warn("推理强度 [{}] 不是 DeepSeek 原生通道的可选值 {}，已忽略", effort,
                    Arrays.toString(DeepSeekApi.ChatCompletionRequest.ReasoningEffort.values()));
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ReasoningDraftAccess reasoningDraft() {
        return DeepSeekDraftAccess.INSTANCE;
    }

    @Override
    public CacheMetricsAccess cacheMetrics() {
        return DeepSeekCacheMetrics.INSTANCE;
    }

    // ===== 通道私有能力的实现（DeepSeek 类型只出现在本文件内） =====

    /** 草稿读写：走 {@link DeepSeekAssistantMessage#getReasoningContent()} 往返 */
    private enum DeepSeekDraftAccess implements ReasoningDraftAccess {

        INSTANCE;

        @Override
        public String readDraft(AssistantMessage message) {
            return message instanceof DeepSeekAssistantMessage ds ? ds.getReasoningContent() : null;
        }

        @Override
        public AssistantMessage buildHistoryMessage(String visibleText, String reasoning,
                                                    List<AssistantMessage.ToolCall> toolCalls) {
            DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder();
            if (reasoning != null) {
                builder.reasoningContent(reasoning);
            }
            // createRequest() 断言 content 文本非 null（内容可由 tool_calls 单独承载）
            builder.content(visibleText == null ? "" : visibleText);
            if (toolCalls != null && !toolCalls.isEmpty()) {
                builder.toolCalls(toolCalls);
            }
            return builder.build();
        }
    }

    /** 缓存命中：读原生 usage 的 prompt_tokens_details.cached_tokens */
    private enum DeepSeekCacheMetrics implements CacheMetricsAccess {

        INSTANCE;

        @Override
        public Long cachedTokens(Usage usage) {
            try {
                if (usage.getNativeUsage() instanceof DeepSeekApi.Usage dsUsage) {
                    DeepSeekApi.Usage.PromptTokensDetails details = dsUsage.promptTokensDetails();
                    return details != null && details.cachedTokens() != null
                            ? details.cachedTokens().longValue() : null;
                }
            } catch (Exception e) {
                log.debug("DeepSeek 缓存命中 token 提取失败: {}", e.getMessage());
            }
            return null;
        }
    }
}

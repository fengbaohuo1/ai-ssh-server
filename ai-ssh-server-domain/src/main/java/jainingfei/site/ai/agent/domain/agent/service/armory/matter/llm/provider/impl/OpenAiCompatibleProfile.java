package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.impl;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ModelTuning;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Locale;

/**
 * OpenAI 协议通道档案——用<b>协议</b>而不是厂商 SDK 说话，厂商身份只体现在配置里。
 *
 * <p>走的通道是 OpenAI 兼容协议：绝大多数服务（Kimi、通义、本地 vLLM/Ollama、各家中转）
 * 都提供该协议入口，因此换模型 = 改配置，不改代码：
 * <pre>
 *   base-url: https://api.deepseek.com    → DeepSeek（协议入口）
 *   base-url: https://api.moonshot.cn/v1  → Kimi
 *   base-url: http://127.0.0.1:8000/v1    → 本地 vLLM / Ollama
 * </pre>
 *
 * <p>一个必须显式打开的开关：{@code streamUsage(true)} —— OpenAI 协议下 usage（含缓存命中）
 * 只在流式响应末块返回，且要求请求带 {@code stream_options.include_usage=true}；
 * 不开就看不到 token 与缓存命中统计（长会话压缩也会失去真实 promptTokenCount）。
 *
 * <p>本通道的两个出口（与原生通道对等，只是走法不同）：
 * <ul>
 *   <li>草稿：{@code AssistantMessage} 元数据键 {@code reasoningContent} 读写
 *       （实测流式分片几乎每片都带该键）→ 档案不需要自己的 SPI</li>
 *   <li>缓存命中：通用字段 {@code Usage.getCacheReadInputTokens()} 已被本通道填充 → 同样不需要 SPI</li>
 * </ul>
 * 本通道唯一做不到的是"显式关思考"（协议里没有该参数）——但这里 temperature 不受思考模式限制，
 * 所以辅助链路的确定性目标不受影响（详见 {@link ModelTuning}）。
 */
@Slf4j
public class OpenAiCompatibleProfile implements ProviderProfile {

    private static final String ID = ProviderCapabilities.OPENAI_COMPATIBLE;

    private final ChatModel chatModel;
    private final ProviderCapabilities capabilities;

    private OpenAiCompatibleProfile(ChatModel chatModel, ProviderCapabilities capabilities) {
        this.chatModel = chatModel;
        this.capabilities = capabilities;
    }

    /**
     * 装配 OpenAI 协议通道模型。
     *
     * @param config 配置文件 chat-model（base-url / api-key / model）
     * @param tuning 厂商中立调参（reasoningEffort / temperature；thinking 本通道不支持）
     */
    public static OpenAiCompatibleProfile of(AiAgentConfigTableVO.Module.ChatModel config, ModelTuning tuning) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .model(config.getModel())
                // 流式 usage：缓存命中与 token 统计的观测开关（协议要求显式打开）
                .streamUsage(true);

        String reasoningEffort = tuning == null ? null : tuning.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            warnUnknownEffort(reasoningEffort);
            // 原样透传：仅推理模型生效，非推理模型由服务端忽略（未知取值也不本地抛错，交给服务端裁决）
            options.reasoningEffort(reasoningEffort);
        }
        if (tuning != null && tuning.getTemperature() != null) {
            options.temperature(tuning.getTemperature());
        }

        ChatModel chatModel = OpenAiChatModel.builder().options(options.build()).build();
        log.info("OpenAI 协议通道装配完成 | model:{} | baseUrl:{} | reasoningEffort:{} | temperature:{}",
                config.getModel(), config.getBaseUrl(),
                StringUtils.defaultIfBlank(reasoningEffort, "默认"),
                tuning == null || tuning.getTemperature() == null ? "默认" : tuning.getTemperature());

        return new OpenAiCompatibleProfile(chatModel, ProviderCapabilities.forProvider(ID));
    }

    /** 取值不在声明集合内时告警（仍原样透传——协议通道不知道对面是谁，只能交给服务端裁决） */
    private static void warnUnknownEffort(String effort) {
        List<String> known = ProviderCapabilities.forProvider(ID).getReasoningEffortValues();
        if (!known.contains(effort.trim().toLowerCase(Locale.ROOT))) {
            log.warn("推理强度 [{}] 不在 OpenAI 协议通道的常见取值 {} 内，将原样透传（由服务端裁决）", effort, known);
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

    // 草稿读法与缓存命中读法都返回 null（走 Spring AI 的通用出口）：
    //   读草稿  → AssistantMessage 元数据键 reasoningContent
    //   回放    → 同上（协议层据此回写 reasoning_content）
    //   缓存命中 → 通用 Usage.getCacheReadInputTokens()
}

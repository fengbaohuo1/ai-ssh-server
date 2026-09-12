package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 通道能力声明——<b>把"这条通道能做什么"变成数据</b>，供装配期校验、日志与前端展示共用。
 *
 * <p>为什么需要它：Spring AI 统一了共同面（调用/消息/工具/流式/基础 token），
 * 但厂商私有能力并不统一——有多有少、有全有缺，且缺失时表现是"空值"而不是"报错"。
 * 没有声明就会静默降级：模型明明没给推理内容，我们还以为它没思考。
 *
 * <p>各能力位的判据（源码 + 实测双向确认，逐条可复核；实测入口
 * {@code TwoChannelCapabilityLiveTest}，2026-09 跑在 deepseek-flash 上）：
 * <pre>
 *   reasoningDraftStreaming  流式响应里能否拿到推理内容——两条通道都能，只是出口不同
 *       deepseek-native  ✔ DeepSeekAssistantMessage.reasoningContent（专有消息类型）
 *       openai-compatible ✔ AssistantMessage 元数据键 reasoningContent
 *                            （实测 259/260 个分片带该键；此前据 mergeDeltas 源码误判为"拿不到"，
 *                             实测纠正——见踩坑记录）
 *   reasoningDraftReplay     请求侧能否把推理内容回写给服务端
 *       deepseek-native  ✔ 专有消息类型（createRequest 用 instanceof 读）
 *       openai-compatible ✔ 元数据键 reasoningContent → reasoning_content 线上字段
 *   cacheMetricsGeneric      通用字段 Usage.getCacheReadInputTokens() 是否被填充
 *       deepseek-native  ✘ 源码未填 → 需读原生 DeepSeekApi.Usage
 *       openai-compatible ✔ getDefaultUsage 填 prompt_tokens_details.cached_tokens
 *   thinkingToggle           能否显式开关思考
 *       deepseek-native  ✔ Thinking.ENABLED/DISABLED
 *       openai-compatible ✘ 协议里没有对应参数（但该通道 temperature 不受思考模式限制，辅助链路无需关思考）
 *   reasoningEffortValues    该通道接受的推理强度取值（空 = 不声明；仅供日志与前端展示，
 *                            具体取值处理在各通道内——协议通道原样透传，原生通道做枚举映射）
 *       deepseek-native  [high, max]
 *       openai-compatible [none, minimal, low, medium, high, xhigh, max]
 * </pre>
 *
 * <p>结论：两个通道在"草稿流式/草稿回放/缓存命中"上<b>能力对等、出口不同</b>；
 * 真正的差异只剩"能否显式关思考"与"推理强度取值集合"。通道区分的价值因此不在"谁多一个功能"，
 * 而在<b>每家的私有出口都被显式声明并各走各的实现</b>——上层永远只读能力位与两个 SPI。
 */
@Value
@Builder
public class ProviderCapabilities {

    /** 默认通道（配置未指定 provider 时使用） */
    public static final String OPENAI_COMPATIBLE = "openai-compatible";

    /** DeepSeek 原生通道 */
    public static final String DEEPSEEK_NATIVE = "deepseek-native";

    /** 通道 id（与配置 chat-model.provider 一致） */
    String providerId;

    /** 通道展示名（日志与前端配置回传） */
    String displayName;

    boolean reasoningDraftStreaming;
    boolean reasoningDraftReplay;
    boolean cacheMetricsGeneric;
    boolean thinkingToggle;

    /** 该通道可接受的推理强度取值（小写）；空列表 = 不校验 */
    @Builder.Default
    List<String> reasoningEffortValues = List.of();

    /** 一句人话说明（装配日志与前端展示用） */
    String note;

    /**
     * 按通道 id 取能力声明（唯一出处：装配期校验、日志、配置回传都从这里读，避免两处声明漂移）。
     *
     * @param providerId 通道 id；null 或空取默认通道
     */
    public static ProviderCapabilities forProvider(String providerId) {
        String id = (providerId == null || providerId.isBlank()) ? OPENAI_COMPATIBLE : providerId.trim();
        return switch (id) {
            case DEEPSEEK_NATIVE -> ProviderCapabilities.builder()
                    .providerId(DEEPSEEK_NATIVE)
                    .displayName("DeepSeek 原生通道")
                    .reasoningDraftStreaming(true)
                    .reasoningDraftReplay(true)
                    .cacheMetricsGeneric(false)
                    .thinkingToggle(true)
                    .reasoningEffortValues(List.of("high", "max"))
                    .note("DeepSeek 原生通道：草稿走专有消息类型，缓存命中读原生 usage；可显式开关思考")
                    .build();
            case OPENAI_COMPATIBLE -> ProviderCapabilities.builder()
                    .providerId(OPENAI_COMPATIBLE)
                    .displayName("OpenAI 协议通道")
                    .reasoningDraftStreaming(true)
                    .reasoningDraftReplay(true)
                    .cacheMetricsGeneric(true)
                    .thinkingToggle(false)
                    .reasoningEffortValues(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"))
                    .note("OpenAI 协议通道：草稿走元数据键，缓存命中读通用字段；协议里没有显式思考开关")
                    .build();
            default -> throw new IllegalArgumentException("未知模型通道: " + providerId
                    + "（可选值：" + OPENAI_COMPATIBLE + ", " + DEEPSEEK_NATIVE + "）");
        };
    }

    /** 装配日志用的一行能力摘要 */
    public String summary() {
        return "草稿流式=" + mark(reasoningDraftStreaming)
                + " 草稿回放=" + mark(reasoningDraftReplay)
                + " 缓存命中=" + (cacheMetricsGeneric ? "通用字段" : "原生 usage")
                + " 思考开关=" + mark(thinkingToggle)
                + " 推理强度=" + (reasoningEffortValues.isEmpty() ? "不校验" : reasoningEffortValues);
    }

    private static String mark(boolean supported) {
        return supported ? "✔" : "✘";
    }
}

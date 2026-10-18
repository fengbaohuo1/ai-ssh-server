package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider;

import lombok.Builder;
import lombok.Value;

/**
 * 模型调参（厂商中立）——描述"这一个模型实例想要什么"，不出现任何厂商类型。
 *
 * <p>四个调用方用同一套表达：
 * <pre>
 *   主链路（ChatModelNode）        ：reasoningEffort = 配置文件取值（如 High）
 *   规划器（PlannerAgentBuilder）  ：不设推理强度（要快、要稳的 JSON 输出）
 *   意图识别（LLMIntentClassifier）：temperature = 0.1（要确定性）
 * </pre>
 *
 * <p>字段为 null = 不设置（交给服务端默认）。三个字段都是<b>参数语义</b>而不是厂商字段名：
 * {@code reasoningEffort} 与 {@code temperature} 是 OpenAI 协议的通用参数，
 * {@code thinking} 是"要不要显式开关思考"的意图——能否落实由通道能力决定
 * （{@link ProviderCapabilities#isThinkingToggle()}）：原生通道会把它变成厂商参数，
 * 协议通道做不到就告警忽略，而不是让我们在某处硬编码某家的字段名。
 */
@Value
@Builder
public class ModelTuning {

    /**
     * 推理强度（配置文件 chat-model.reasoning-effort 原样传入，如 High/medium/minimal）；
     * null 或空 = 不设置。仅推理模型生效，非推理模型由服务端忽略。
     */
    String reasoningEffort;

    /** 采样温度；null = 不设置 */
    Double temperature;

    /** 单次回复最大 token 数；null = 不设置 */
    Integer maxTokens;

    /** 核采样 top-p；null = 不设置 */
    Double topP;

    /**
     * 是否显式开关思考（true=开启 / false=关闭 / null=跟随服务端默认）。
     * <p>只有支持该能力的通道（{@link ProviderCapabilities#isThinkingToggle()}）才会落实。
     */
    Boolean thinking;

    /** 全部不设置（跟随服务端默认） */
    public static ModelTuning defaults() {
        return ModelTuning.builder().build();
    }

    /**
     * 只要确定性的调参（辅助链路：规划器传 null，意图识别传 0.1）。
     *
     * <p>顺带把思考关掉，原因来自 DeepSeek 原生通道的源码事实
     * （{@code DeepSeekChatModel#validateThinkingParameters}）：<b>思考模式下 temperature 被服务端静默忽略</b>
     * ——只记一条 WARN，请求照发但参数不生效。辅助链路要的就是低温确定性，
     * 所以"关思考"是达成该意图的必要条件，而不是额外的性能优化。
     * 不支持该开关的通道（协议通道）忽略即可：那里 temperature 本来就生效，目标已经达成。
     */
    public static ModelTuning deterministic(Double temperature) {
        return ModelTuning.builder()
                .temperature(temperature)
                .thinking(false)
                .build();
    }
}

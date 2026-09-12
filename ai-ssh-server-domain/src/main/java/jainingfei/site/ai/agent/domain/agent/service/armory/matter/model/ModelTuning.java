package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import lombok.Builder;
import lombok.Value;

/**
 * 模型调参（厂商中立）——描述"这一个模型实例想要什么"，不出现任何厂商类型。
 *
 * <p>三处调用方用同一套表达：
 * <pre>
 *   主链路（ChatModelNode）        ：reasoningEffort = 配置文件取值（如 High）
 *   规划器（PlannerAgentBuilder）  ：不设推理强度（要快、要稳的 JSON 输出）
 *   意图识别（LLMIntentClassifier）：temperature = 0.1（要确定性）
 * </pre>
 *
 * <p>字段为 null = 不设置（交给服务端默认）。两个字段都是协议级参数
 * （OpenAI 协议的 {@code reasoning_effort} / {@code temperature}），
 * 所以这里不需要"厂商私有开关"——例如"显式关闭思考"在协议里没有对应物，
 * 需要时应该走服务端默认或由 provider 侧配置决定，而不是让我们认识某家的字段名。
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

    /** 全部不设置（跟随服务端默认） */
    public static ModelTuning defaults() {
        return ModelTuning.builder().build();
    }

    /** 只要确定性的调参（辅助链路：规划器传 null，意图识别传 0.1） */
    public static ModelTuning deterministic(Double temperature) {
        return ModelTuning.builder()
                .temperature(temperature)
                .build();
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import lombok.Builder;
import lombok.Value;

/**
 * 模型调参（厂商中立）——描述"这一个模型实例想要什么"，不出现任何厂商类型。
 *
 * <p>三处调用方用同一套表达：
 * <pre>
 *   主链路（ChatModelNode）   ：thinking=null（跟随服务端默认）+ reasoningEffort=yml 配置
 *   规划器（PlannerAgentBuilder）：thinking=false + 不设 reasoningEffort（要快、要稳的 JSON 输出）
 *   意图识别（LLMIntentClassifier）：thinking=false + temperature=0.1（要确定性）
 * </pre>
 *
 * <p>字段为 null = 不设置（交给 provider 默认值），由实现类映射成厂商自己的枚举/开关。
 */
@Value
@Builder
public class ModelTuning {

    /** 是否开启思考；null = 不设置（跟随 provider 默认） */
    Boolean thinking;

    /**
     * 推理强度（配置文件的 chat-model.reasoning-effort 原样传入，如 High/medium）；
     * null 或空 = 不设置。仅推理模型生效，实现类需要对不识别的取值降级告警而不是抛异常。
     */
    String reasoningEffort;

    /** 采样温度；null = 不设置 */
    Double temperature;

    /** 全部不设置（跟随 provider 默认） */
    public static ModelTuning defaults() {
        return ModelTuning.builder().build();
    }

    /** 关闭思考的确定性调参（辅助链路：规划器/意图识别） */
    public static ModelTuning deterministic(Double temperature) {
        return ModelTuning.builder()
                .thinking(false)
                .temperature(temperature)
                .build();
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型工厂——装配层唯一的"按配置造模型"入口。
 *
 * <p>为什么需要工厂：Spring AI 的自动配置是"单例 bean + 静态 spring.ai.* 配置"，
 * 而我们的模型来自配置文件的多张表（运行期才能确定 base-url/api-key/model），
 * 因此只能自己建实例；工厂把这份知识集中到一处，调用方只拿到 {@link ChatModelBundle}。
 *
 * <p>调用矩阵（三条链路共用同一入口，仅调参不同）：
 * <pre>
 *   createMain(cfg)                            主链路     thinking 跟随默认 + yml reasoning-effort
 *   create(cfg, deterministic(null))           规划器     关思考（要快、要稳的 JSON 输出）
 *   create(cfg, tuning(temperature=0.1))       意图识别   与原有 OpenAI 兼容通道行为对齐（仅温度）
 * </pre>
 *
 * <p>接第二家模型的做法：新增一个 {@link ChatModelBundle} 实现，在本工厂按
 * provider 字段（如 chat-model 增加 provider 配置）选择实现——适配器与派发/编排层零改动。
 */
@Slf4j
@Component
public class ChatModelFactory {

    /**
     * 主链路模型：思考跟随服务端默认，只把配置文件里的推理强度透传下去。
     *
     * @param chatModelConfig 配置文件 chat-model
     */
    public ChatModelBundle createMain(AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
        return create(chatModelConfig, ModelTuning.builder()
                .reasoningEffort(chatModelConfig.getReasoningEffort())
                .build());
    }

    /**
     * 通用入口：按配置 + 调参造模型包。
     *
     * @param chatModelConfig 配置文件 chat-model
     * @param tuning          厂商中立调参
     */
    public ChatModelBundle create(AiAgentConfigTableVO.Module.ChatModel chatModelConfig, ModelTuning tuning) {
        // 目前只有 DeepSeek 一个 provider 实现；将来按配置增加 provider 分支即可
        return DeepSeekChatModelBundle.of(chatModelConfig, tuning);
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.impl.DeepSeekNativeProfile;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.impl.OpenAiCompatibleProfile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 模型工厂——装配层唯一的"按配置造模型"入口，选的是<b>通道</b>而不是"某家的类"。
 *
 * <p>为什么需要工厂：Spring AI 的自动配置是"单例 bean + 静态 spring.ai.* 配置"，
 * 而我们的模型来自配置文件的多张表（运行期才能确定 base-url/api-key/model/通道），
 * 只能自己建实例；工厂把"通道 id → 档案实现"这份知识集中到一处。
 *
 * <p>三条链路共用同一入口，只差调参：
 * <pre>
 *   createMain(cfg)                            主链路   reasoningEffort 取配置
 *   create(cfg, deterministic(null))           规划器   不设推理强度（要快、要稳的 JSON）
 *   create(cfg, tuning(temperature = 0.1))     意图识别 只要确定性
 * </pre>
 *
 * <p>三条出口：
 * <pre>
 *   ① 选档案    chat-model.provider → {@link ProviderProfile}（未配置取默认通道）
 *   ② 能力日志  装配期打印"这条通道能做什么"（能力表 + 一句说明），请求不到的能力留痕（DEBUG）
 *   ③ 未知通道  fail-fast（列出可选值），而不是悄悄退回默认通道
 * </pre>
 *
 * <p>加一条通道 = 新增一个 {@link ProviderProfile} 实现 + 在 {@link #assemble} 的 switch 里加一行；
 * 适配器、装配节点、派发与编排层零改动。
 */
@Slf4j
@Component
public class ChatModelFactory {

    /**
     * 主链路模型：推理强度取配置文件。
     *
     * @param chatModelConfig 配置文件 chat-model（provider / base-url / api-key / model / reasoning-effort）
     */
    public ProviderProfile createMain(AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
        return create(chatModelConfig, ModelTuning.builder()
                .reasoningEffort(chatModelConfig.getReasoningEffort())
                .temperature(chatModelConfig.getTemperature())
                .maxTokens(chatModelConfig.getMaxTokens())
                .topP(chatModelConfig.getTopP())
                .build());
    }

    /**
     * 通用入口：按配置 + 调参装配通道档案。
     *
     * @param chatModelConfig 配置文件 chat-model
     * @param tuning          调参（推理强度 / 温度 / 思考开关），null = 全部跟随服务端默认
     */
    public ProviderProfile create(AiAgentConfigTableVO.Module.ChatModel chatModelConfig, ModelTuning tuning) {
        // 未知通道在这里就抛错（并列出可选值）：配置写错要在装配期炸，不能悄悄换通道
        ProviderCapabilities capabilities = ProviderCapabilities.forProvider(chatModelConfig.getProvider());
        ProviderProfile profile = assemble(capabilities, chatModelConfig, tuning);

        log.info("模型装配完成 | provider:{} | model:{} | baseUrl:{}",
                profile.id(), chatModelConfig.getModel(), chatModelConfig.getBaseUrl());
        log.info("通道能力 | {} | {}", profile.id(), capabilities.summary());
        log.info("通道说明 | {} | {}", profile.id(), capabilities.getNote());
        noteUnsupported(profile, tuning);

        return profile;
    }

    /** 通道 id → 档案实现（新增通道只改这一处） */
    private ProviderProfile assemble(ProviderCapabilities capabilities,
                                     AiAgentConfigTableVO.Module.ChatModel config,
                                     ModelTuning tuning) {
        return switch (capabilities.getProviderId()) {
            case ProviderCapabilities.DEEPSEEK_NATIVE -> DeepSeekNativeProfile.of(config, tuning);
            case ProviderCapabilities.OPENAI_COMPATIBLE -> OpenAiCompatibleProfile.of(config, tuning);
            default -> throw new IllegalArgumentException("通道 " + capabilities.getProviderId() + " 未注册档案实现");
        };
    }

    /**
     * 配置请求了本通道做不到的能力时留一条记录（<b>DEBUG 而不是 WARN</b>）。
     *
     * <p>为什么降级成 DEBUG：目前唯一会"请求不到"的是 {@link ModelTuning#getThinking()}，
     * 而它只在辅助链路里被设置（目的是让 temperature 生效）。协议通道虽然表达不了"关思考"，
     * 但那里 temperature 本来就生效——<b>意图已经达成</b>，再告警就是误导。
     * 真正会造成静默失效的组合（原生通道思考模式忽略 temperature）由该通道自己按源码事实告警。
     *
     * <p>取值类问题（推理强度）同理放在各通道内：协议通道原样透传、原生通道做枚举映射，
     * 两种语义不同，放在这里会误报。
     */
    private void noteUnsupported(ProviderProfile profile, ModelTuning tuning) {
        if (tuning == null || tuning.getThinking() == null) {
            return;
        }
        if (!profile.capabilities().isThinkingToggle()) {
            log.debug("通道 [{}] 无显式思考开关，thinking={} 未下发（该通道的 temperature 不受思考模式限制）",
                    profile.id(), tuning.getThinking());
        }
    }

    /** 配置未指定通道时的默认值（供配置回传等无装配场景使用） */
    public static String defaultProviderId() {
        return ProviderCapabilities.OPENAI_COMPATIBLE;
    }

    /** 规范化通道 id（空 → 默认通道），供配置回传与日志使用 */
    public static String normalizeProviderId(String providerId) {
        return StringUtils.isBlank(providerId) ? defaultProviderId() : providerId.trim();
    }
}

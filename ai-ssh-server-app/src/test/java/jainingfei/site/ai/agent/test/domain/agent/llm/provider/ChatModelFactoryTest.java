package jainingfei.site.ai.agent.test.domain.agent.llm.provider;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ChatModelFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ModelTuning;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型工厂（ChatModelFactory）单元测试——通道选择与能力装配，<b>不发起任何网络调用</b>。
 *
 * <p>构造 profile 只是创建 Spring AI 的模型客户端对象（Retrofit/WebClient 实例），
 * 不会连接服务端；因此这里可以安全地走完整装配路径。apiKey 用假的 {@code sk-test}。
 */
public class ChatModelFactoryTest {

    private final ChatModelFactory factory = new ChatModelFactory();

    private AiAgentConfigTableVO.Module.ChatModel config(String provider) {
        AiAgentConfigTableVO.Module.ChatModel config = new AiAgentConfigTableVO.Module.ChatModel();
        config.setProvider(provider);
        config.setBaseUrl("http://127.0.0.1:9/v1");
        config.setApiKey("sk-test");
        config.setModel("test-model");
        return config;
    }

    // ===== 通道 id 规范化 =====

    @Test
    @DisplayName("normalizeProviderId：null/空串/空白 → 默认通道")
    public void test_normalize_blank() {
        assertEquals("openai-compatible", ChatModelFactory.normalizeProviderId(null));
        assertEquals("openai-compatible", ChatModelFactory.normalizeProviderId(""));
        assertEquals("openai-compatible", ChatModelFactory.normalizeProviderId("   "));
        assertEquals("openai-compatible", ChatModelFactory.defaultProviderId());
    }

    @Test
    @DisplayName("normalizeProviderId：去两侧空格，保留原大小写（通道 id 是精确匹配）")
    public void test_normalize_trims() {
        assertEquals("deepseek-native", ChatModelFactory.normalizeProviderId("  deepseek-native "));
        assertEquals("DeepSeek-Native", ChatModelFactory.normalizeProviderId("DeepSeek-Native"));
    }

    // ===== 通道装配 =====

    @Test
    @DisplayName("provider=null 装配默认通道 openai-compatible，模型实例非空")
    public void test_default_channel_profile() {
        ProviderProfile profile = factory.create(config(null), ModelTuning.defaults());

        assertEquals("openai-compatible", profile.id());
        assertNotNull(profile.chatModel(), "必须真的造出模型实例");
        assertTrue(profile.capabilities().isCacheMetricsGeneric());
        assertEquals("OpenAI 协议通道", profile.displayName());
        assertNull(profile.reasoningDraft(), "协议通道走通用元数据约定，不提供专有读法");
        assertNull(profile.cacheMetrics(), "协议通道通用字段已够用");
    }

    @Test
    @DisplayName("provider=deepseek-native 装配原生通道，id 与两项专有读法都就位")
    public void test_deepseek_native_profile() {
        ProviderProfile profile = factory.create(config("deepseek-native"), ModelTuning.defaults());

        assertEquals("deepseek-native", profile.id());
        assertNotNull(profile.chatModel(), "原生通道模型实例必须非空（构造不联网）");
        assertFalse(profile.capabilities().isCacheMetricsGeneric());
        assertTrue(profile.capabilities().isThinkingToggle());
        assertNotNull(profile.reasoningDraft(), "原生通道必须提供专有草稿读法");
        assertNotNull(profile.cacheMetrics(), "原生通道必须提供原生 usage 缓存读法");
    }

    @Test
    @DisplayName("未知 provider 在装配期直接抛错并列出可选值（不静默退回默认通道）")
    public void test_unknown_provider_fails_fast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(config("kimi-native"), ModelTuning.defaults()));

        assertTrue(ex.getMessage().contains("kimi-native"), ex.getMessage());
        assertTrue(ex.getMessage().contains("openai-compatible"), ex.getMessage());
        assertTrue(ex.getMessage().contains("deepseek-native"), ex.getMessage());
    }

    @Test
    @DisplayName("tuning=null 时装配仍然成功（全部跟随服务端默认）")
    public void test_null_tuning_is_safe() {
        assertDoesNotThrow(() -> assertEquals("deepseek-native",
                factory.create(config("deepseek-native"), null).id()));
        assertDoesNotThrow(() -> assertEquals("openai-compatible",
                factory.create(config(null), null).id()));
    }

    // ===== 调参 / 能力不匹配 =====

    @Test
    @DisplayName("协议通道收到 thinking 开关时只留痕不抛错（该通道 temperature 本就生效）")
    public void test_thinking_on_protocol_channel_does_not_throw() {
        ProviderProfile profile = assertDoesNotThrow(() ->
                factory.create(config("openai-compatible"), ModelTuning.builder().thinking(true).build()));

        assertEquals("openai-compatible", profile.id());
        assertFalse(profile.capabilities().isThinkingToggle(), "能力位仍如实声明不支持");
    }

    @Test
    @DisplayName("ModelTuning.deterministic 现在同时关闭思考（辅助链路要的是温度真的生效）")
    public void test_deterministic_tuning_disables_thinking() {
        ModelTuning tuning = ModelTuning.deterministic(0.1);

        assertEquals(0.1, tuning.getTemperature().doubleValue());
        assertEquals(Boolean.FALSE, tuning.getThinking(),
                "DeepSeek 原生通道思考模式下 temperature 会被服务端静默忽略，因此必须关思考");
    }

    @Test
    @DisplayName("create(config, deterministic(0.1)) 在两条通道上都不抛错")
    public void test_deterministic_tuning_both_channels() {
        assertDoesNotThrow(() -> factory.create(config("openai-compatible"), ModelTuning.deterministic(0.1)));
        assertDoesNotThrow(() -> factory.create(config("deepseek-native"), ModelTuning.deterministic(0.1)));
    }

    @Test
    @DisplayName("原生通道设置 temperature 但不显式关思考时不抛错（仅由通道自行告警）")
    public void test_native_channel_temperature_without_thinking_flag() {
        assertDoesNotThrow(() -> factory.create(config("deepseek-native"),
                ModelTuning.builder().temperature(0.7).build()));
    }

    @Test
    @DisplayName("原生通道：合法推理强度 High 被映射，未知取值被忽略且不抛错")
    public void test_native_channel_reasoning_effort_mapping() {
        assertDoesNotThrow(() -> factory.create(config("deepseek-native"),
                ModelTuning.builder().reasoningEffort("High").build()));
        assertDoesNotThrow(() -> factory.create(config("deepseek-native"),
                ModelTuning.builder().reasoningEffort("medium").build()),
                "medium 不是原生枚举取值，应只告警忽略");
        assertDoesNotThrow(() -> factory.create(config("deepseek-native"),
                ModelTuning.builder().reasoningEffort("   ").build()));
    }

    @Test
    @DisplayName("协议通道：任意推理强度原样透传，不本地抛错")
    public void test_protocol_channel_reasoning_effort_passthrough() {
        assertDoesNotThrow(() -> factory.create(config("openai-compatible"),
                ModelTuning.builder().reasoningEffort("high").build()));
        assertDoesNotThrow(() -> factory.create(config("openai-compatible"),
                ModelTuning.builder().reasoningEffort("ULTRA-THINK").build()),
                "未知取值交给服务端裁决");
    }

    // ===== createMain =====

    @Test
    @DisplayName("createMain 取配置里的 reasoningEffort 并装配出对应通道的档案")
    public void test_create_main_uses_config_reasoning_effort() {
        AiAgentConfigTableVO.Module.ChatModel config = config("deepseek-native");
        config.setReasoningEffort("high");

        ProviderProfile profile = assertDoesNotThrow(() -> factory.createMain(config));
        assertEquals("deepseek-native", profile.id());
        assertEquals(ProviderCapabilities.forProvider("deepseek-native").getReasoningEffortValues(),
                profile.capabilities().getReasoningEffortValues());
        assertNotNull(profile.chatModel());
    }

    @Test
    @DisplayName("createMain 在 provider 缺省时落到默认通道")
    public void test_create_main_defaults_channel() {
        AiAgentConfigTableVO.Module.ChatModel config = config(null);
        config.setReasoningEffort("medium");

        assertEquals("openai-compatible", factory.createMain(config).id());
    }
}

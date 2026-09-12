package jainingfei.site.ai.agent.test.domain.agent.llm.provider;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道能力声明（ProviderCapabilities）单元测试——纯数据，无网络无 Spring。
 *
 * <p>能力声明是"装配期校验 / 日志 / 前端展示"的唯一出处，因此这里把每条通道的
 * 能力位逐条固定下来：任何一位被误改都会导致线上静默降级（例如以为能拿到草稿却拿不到）。
 */
public class ProviderCapabilitiesTest {

    @Test
    @DisplayName("forProvider(null / 空串 / 空白) 一律落到默认通道 openai-compatible")
    public void test_blank_provider_falls_back_to_default() {
        ProviderCapabilities fromNull = ProviderCapabilities.forProvider(null);
        ProviderCapabilities fromEmpty = ProviderCapabilities.forProvider("");
        ProviderCapabilities fromBlank = ProviderCapabilities.forProvider("   ");

        assertEquals(ProviderCapabilities.OPENAI_COMPATIBLE, fromNull.getProviderId());
        assertEquals(ProviderCapabilities.OPENAI_COMPATIBLE, fromEmpty.getProviderId());
        assertEquals(ProviderCapabilities.OPENAI_COMPATIBLE, fromBlank.getProviderId());
    }

    @Test
    @DisplayName("forProvider 会 trim 两侧空格（配置文件手写容易带空格）")
    public void test_provider_id_trimmed() {
        assertEquals(ProviderCapabilities.DEEPSEEK_NATIVE,
                ProviderCapabilities.forProvider("  deepseek-native  ").getProviderId());
        assertEquals(ProviderCapabilities.OPENAI_COMPATIBLE,
                ProviderCapabilities.forProvider(" openai-compatible ").getProviderId());
    }

    @Test
    @DisplayName("deepseek-native 能力位：草稿流式✔ 草稿回放✔ 缓存通用✘ 思考开关✔ 推理强度 [high, max]")
    public void test_deepseek_native_capabilities() {
        ProviderCapabilities capabilities = ProviderCapabilities.forProvider("deepseek-native");

        assertTrue(capabilities.isReasoningDraftStreaming(), "原生通道流式也能拿到推理内容");
        assertTrue(capabilities.isReasoningDraftReplay(), "专有消息类型可回放推理内容");
        assertFalse(capabilities.isCacheMetricsGeneric(), "通用 Usage 字段未被填充，需读原生 usage");
        assertTrue(capabilities.isThinkingToggle(), "Thinking.ENABLED/DISABLED 可显式开关");
        assertEquals(List.of("high", "max"), capabilities.getReasoningEffortValues());
        assertEquals("DeepSeek 原生通道", capabilities.getDisplayName());
        assertTrue(capabilities.getNote().contains("DeepSeek"), capabilities.getNote());
    }

    @Test
    @DisplayName("openai-compatible 能力位：草稿流式✔ 草稿回放✔ 缓存通用✔ 思考开关✘ 推理强度七档")
    public void test_openai_compatible_capabilities() {
        ProviderCapabilities capabilities = ProviderCapabilities.forProvider("openai-compatible");

        // 实测纠正：曾据 mergeDeltas 源码判定协议通道"流式拿不到草稿"，
        // 真实调用（TwoChannelCapabilityLiveTest，259/260 分片带元数据键）证明拿得到——
        // 能力表以实测为准，源码结论只是线索。
        assertTrue(capabilities.isReasoningDraftStreaming(), "元数据键 reasoningContent 在流式分片里就有");
        assertTrue(capabilities.isReasoningDraftReplay(), "走 AssistantMessage 元数据键回放");
        assertTrue(capabilities.isCacheMetricsGeneric(), "prompt_tokens_details.cached_tokens 会填通用字段");
        assertFalse(capabilities.isThinkingToggle(), "协议里没有显式关思考参数");
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                capabilities.getReasoningEffortValues());
        assertEquals("OpenAI 协议通道", capabilities.getDisplayName());
    }

    @Test
    @DisplayName("未知通道 id 抛 IllegalArgumentException，且报错信息列出全部可选值")
    public void test_unknown_provider_fails_fast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProviderCapabilities.forProvider("azure-openai"));

        assertTrue(ex.getMessage().contains("azure-openai"), ex.getMessage());
        assertTrue(ex.getMessage().contains("openai-compatible"), "报错必须列出可选值：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("deepseek-native"), "报错必须列出可选值：" + ex.getMessage());
    }

    @Test
    @DisplayName("summary() 反映各能力位：勾叉随能力变化，缓存命中说明区分通用字段与原生 usage")
    public void test_summary_reflects_flags() {
        String deepseek = ProviderCapabilities.forProvider("deepseek-native").summary();
        assertTrue(deepseek.contains("草稿流式=✔"), deepseek);
        assertTrue(deepseek.contains("草稿回放=✔"), deepseek);
        assertTrue(deepseek.contains("缓存命中=原生 usage"), deepseek);
        assertTrue(deepseek.contains("思考开关=✔"), deepseek);
        assertTrue(deepseek.contains("[high, max]"), deepseek);

        String openai = ProviderCapabilities.forProvider("openai-compatible").summary();
        assertTrue(openai.contains("草稿流式=✔"), openai);
        assertTrue(openai.contains("缓存命中=通用字段"), openai);
        assertTrue(openai.contains("思考开关=✘"), openai);
    }

    @Test
    @DisplayName("summary() 在推理强度列表为空时显示\"不校验\"")
    public void test_summary_without_effort_values() {
        ProviderCapabilities capabilities = ProviderCapabilities.builder()
                .providerId("custom")
                .displayName("自定义通道")
                .reasoningEffortValues(List.of())
                .build();

        assertTrue(capabilities.summary().contains("推理强度=不校验"), capabilities.summary());
        assertTrue(capabilities.getReasoningEffortValues().isEmpty());
    }

    @Test
    @DisplayName("常量值与配置里的通道 id 字面量保持一致（配置契约）")
    public void test_provider_id_constants() {
        assertEquals("openai-compatible", ProviderCapabilities.OPENAI_COMPATIBLE);
        assertEquals("deepseek-native", ProviderCapabilities.DEEPSEEK_NATIVE);
    }
}

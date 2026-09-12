package jainingfei.site.ai.agent.test.domain.agent.llm;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.CacheMetricsAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.UsageReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Usage 观测（UsageReporter）单元测试——token / 缓存命中 / usageMetadata 映射，纯内存无网络。
 *
 * <p>三条出口的语义：
 * <pre>
 *   observe     流式逐块观察，保留"最后一次非空 usage"（末块才有完整统计，而末块常无文本）
 *   log         只打日志，缺 usage 时安全返回，绝不打断主链路
 *   toMetadata  spring-ai Usage → genai usageMetadata（ADK 压缩引擎读真实 promptTokenCount）
 * </pre>
 */
public class UsageReporterTest {

    private final UsageReporter reporter = new UsageReporter();

    /** 手写 Usage 假实现——比 Mockito 更直白，也不引入额外依赖 */
    private static class FakeUsage implements Usage {
        private final Integer promptTokens;
        private final Integer completionTokens;
        private final Integer totalTokens;
        private final Long cacheReadInputTokens;
        private final Object nativeUsage;

        FakeUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens, Long cacheReadInputTokens) {
            this(promptTokens, completionTokens, totalTokens, cacheReadInputTokens, null);
        }

        FakeUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                  Long cacheReadInputTokens, Object nativeUsage) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.cacheReadInputTokens = cacheReadInputTokens;
            this.nativeUsage = nativeUsage;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Integer getTotalTokens() {
            return totalTokens;
        }

        @Override
        public Object getNativeUsage() {
            return nativeUsage;
        }

        @Override
        public Long getCacheReadInputTokens() {
            return cacheReadInputTokens;
        }
    }

    private static ChatResponse responseWith(Usage usage) {
        return new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("x").toolCalls(List.of()).build())),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    // ===== observe =====

    @Test
    @DisplayName("observe：保留最后一次非空 usage（末块统计最完整）")
    public void test_observe_keeps_last_non_null_usage() {
        AtomicReference<Usage> ref = new AtomicReference<>();
        Usage first = new FakeUsage(10, 1, 11, null);
        Usage last = new FakeUsage(100, 20, 120, 64L);

        reporter.observe(responseWith(first), ref);
        assertSame(first, ref.get());

        reporter.observe(responseWith(null), ref);
        assertSame(first, ref.get(), "usage 为 null 的 chunk 不得清空已捕获的统计");

        reporter.observe(responseWith(last), ref);
        assertSame(last, ref.get(), "后面的非空 usage 必须覆盖前面的");
    }

    @Test
    @DisplayName("observe：null 响应安全返回，引用保持原值")
    public void test_observe_null_safe() {
        AtomicReference<Usage> ref = new AtomicReference<>();
        assertDoesNotThrow(() -> reporter.observe(null, ref));
        assertNull(ref.get());
    }

    // ===== cachedTokens =====

    @Test
    @DisplayName("cachedTokens：通用字段 Usage.getCacheReadInputTokens() 优先，通道兜底不被调用")
    public void test_cached_tokens_prefers_generic_field() {
        CacheMetricsAccess access = usage -> 999L;
        assertEquals(64L, reporter.cachedTokens(new FakeUsage(1, 1, 2, 64L), access));
    }

    @Test
    @DisplayName("cachedTokens：通用字段为 null 时回退通道能力（原生通道未填通用字段）")
    public void test_cached_tokens_falls_back_to_access() {
        CacheMetricsAccess access = usage -> 128L;
        assertEquals(128L, reporter.cachedTokens(new FakeUsage(1, 1, 2, null), access));
    }

    @Test
    @DisplayName("cachedTokens：通用字段与通道都拿不到时返回 null，而不是 0")
    public void test_cached_tokens_null_when_unavailable() {
        assertNull(reporter.cachedTokens(new FakeUsage(1, 1, 2, null), null));
        assertNull(reporter.cachedTokens(new FakeUsage(1, 1, 2, null), usage -> null));
        assertNull(reporter.cachedTokens(null, usage -> 5L), "usage 为 null 时直接返回 null");
    }

    // ===== log =====

    @Test
    @DisplayName("log：缺 usage 或缺通道能力都不抛异常（观测不得打断主链路）")
    public void test_log_never_throws() {
        assertDoesNotThrow(() -> reporter.log(null, null));
        assertDoesNotThrow(() -> reporter.log(new FakeUsage(1, 1, 2, null), null));
        assertDoesNotThrow(() -> reporter.log(new FakeUsage(null, null, null, null), usage -> 7L));
        assertDoesNotThrow(() -> reporter.log(new FakeUsage(1, 1, 2, 3L), usage -> {
            throw new IllegalStateException("通道读取失败");
        }), "通道读法抛异常时缓存命中数应降级为 null 并继续");
    }

    // ===== toMetadata =====

    @Test
    @DisplayName("toMetadata：prompt/completion/total/cached 四项完整映射")
    public void test_to_metadata_full_mapping() {
        GenerateContentResponseUsageMetadata metadata =
                reporter.toMetadata(new FakeUsage(120, 30, 150, 64L), null);

        assertEquals(120, metadata.promptTokenCount().orElse(0));
        assertEquals(30, metadata.candidatesTokenCount().orElse(0));
        assertEquals(150, metadata.totalTokenCount().orElse(0));
        assertEquals(64, metadata.cachedContentTokenCount().orElse(0));
    }

    @Test
    @DisplayName("toMetadata：usage 为 null 时返回 null")
    public void test_to_metadata_null_usage() {
        assertNull(reporter.toMetadata(null, null));
    }

    @Test
    @DisplayName("toMetadata：缓存命中缺失时不写 cachedContentTokenCount")
    public void test_to_metadata_without_cache() {
        GenerateContentResponseUsageMetadata metadata =
                reporter.toMetadata(new FakeUsage(10, 5, 15, null), null);

        assertTrue(metadata.cachedContentTokenCount().isEmpty(), "拿不到缓存命中时不得写 0 冒充命中");
        assertEquals(10, metadata.promptTokenCount().orElse(0));
    }

    @Test
    @DisplayName("toMetadata：计数为 null 的字段不落库（不写 0）")
    public void test_to_metadata_partial_fields() {
        GenerateContentResponseUsageMetadata metadata =
                reporter.toMetadata(new FakeUsage(null, 5, null, null), null);

        assertTrue(metadata.promptTokenCount().isEmpty());
        assertTrue(metadata.totalTokenCount().isEmpty());
        assertEquals(5, metadata.candidatesTokenCount().orElse(0));
    }

    @Test
    @DisplayName("toMetadata：通道兜底的缓存命中同样进入 usageMetadata")
    public void test_to_metadata_uses_access_fallback() {
        GenerateContentResponseUsageMetadata metadata =
                reporter.toMetadata(new FakeUsage(10, 5, 15, null), usage -> 42L);

        assertEquals(42, metadata.cachedContentTokenCount().orElse(0));
    }
}

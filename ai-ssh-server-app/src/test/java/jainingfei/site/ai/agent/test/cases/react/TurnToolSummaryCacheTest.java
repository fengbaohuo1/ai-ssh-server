package jainingfei.site.ai.agent.test.cases.react;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jainingfei.site.ai.agent.cases.react.TurnToolSummaryCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TurnToolSummaryCache} 单元测试：跨回合摘要的存取、截断与有界 LRU。
 */
class TurnToolSummaryCacheTest {

    @Test
    @DisplayName("写入后可读回；不同会话互不干扰")
    void test_put_get_roundtrip() {
        TurnToolSummaryCache.put("s-roundtrip-1", "- [成功] executeCommand: df -h → 40%");
        TurnToolSummaryCache.put("s-roundtrip-2", "- [失败] executeCommand: ls /nope → 无此文件");

        assertEquals("- [成功] executeCommand: df -h → 40%", TurnToolSummaryCache.get("s-roundtrip-1"));
        assertEquals("- [失败] executeCommand: ls /nope → 无此文件", TurnToolSummaryCache.get("s-roundtrip-2"));
        assertNull(TurnToolSummaryCache.get("s-roundtrip-unknown"));
    }

    @Test
    @DisplayName("空 sessionId / 空摘要不写入")
    void test_blank_ignored() {
        TurnToolSummaryCache.put(null, "x");
        TurnToolSummaryCache.put("s-blank-1", null);
        TurnToolSummaryCache.put("s-blank-1", "   ");

        assertNull(TurnToolSummaryCache.get(null));
        assertNull(TurnToolSummaryCache.get("s-blank-1"));
    }

    @Test
    @DisplayName("超长摘要按上限截断并加省略号")
    void test_truncate_long_summary() {
        String longSummary = "a".repeat(TurnToolSummaryCache.MAX_SUMMARY_CHARS + 500);
        TurnToolSummaryCache.put("s-long-1", longSummary);

        String value = TurnToolSummaryCache.get("s-long-1");
        assertTrue(value.length() <= TurnToolSummaryCache.MAX_SUMMARY_CHARS + 1,
                "stored length=" + value.length());
        assertTrue(value.endsWith("…"));
    }

    @Test
    @DisplayName("remove 后不再返回")
    void test_remove() {
        TurnToolSummaryCache.put("s-remove-1", "summary");
        TurnToolSummaryCache.remove("s-remove-1");
        assertNull(TurnToolSummaryCache.get("s-remove-1"));
        TurnToolSummaryCache.remove(null);
    }

    @Test
    @DisplayName("容量超限时淘汰最久未访问的会话")
    void test_lru_eviction() {
        // 容量 200：写入 201 个会话，最早写入且未被访问的应被淘汰
        for (int i = 0; i < 201; i++) {
            TurnToolSummaryCache.put("s-lru-" + i, "summary-" + i);
        }
        assertNull(TurnToolSummaryCache.get("s-lru-0"), "最久未访问的会话应被淘汰");
        assertEquals("summary-200", TurnToolSummaryCache.get("s-lru-200"));
        assertTrue(TurnToolSummaryCache.get("s-lru-100") != null);
    }
}

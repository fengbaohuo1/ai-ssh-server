package jainingfei.site.ai.agent.cases.react;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最近一轮工具执行摘要缓存（case 层本地实现，跨回合同一会话传递）。
 *
 * <p>为什么需要它：回合收口时 {@code UserFeedbackNode} 会调用
 * {@code IChatContextService#clearSessionContext} 清掉 domain 的 ToolResultProvider 缓存
 * （避免旧结果污染下一回合），而下一回合 {@code AiCallNode} 组装动态后缀时该 Provider
 * 已无数据，导致 prompt 里的 {@code [工具执行摘要]} 恒为空（V3 回归发现的 bug）。
 * 这里在清理<b>之前</b>把本轮摘要写到独立键下，下一回合读出来补进后缀。
 *
 * <p>为什么放 case 层：domain 的"回合末清理"语义不改变、Provider 生命周期也不被 case 层
 * 二次假设；case 层只需要"最近一轮"这一条跨回合信息，用有界 LRU 承载即可。
 */
public final class TurnToolSummaryCache {

    /** 每个会话保留的最大字符数（超出截断，避免单轮工具输出撑爆后缀） */
    public static final int MAX_SUMMARY_CHARS = 800;

    /** 最多保留多少个会话（LRU 淘汰；会话无显式结束钩子，靠容量兜底防泄漏） */
    private static final int MAX_SESSIONS = 200;

    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new BoundedLruMap(MAX_SESSIONS));

    private TurnToolSummaryCache() {
    }

    /** 写入会话最近一轮的工具执行摘要（超长截断） */
    public static void put(String sessionId, String summary) {
        if (sessionId == null || summary == null || summary.isBlank()) {
            return;
        }
        String value = summary.length() > MAX_SUMMARY_CHARS
                ? summary.substring(0, MAX_SUMMARY_CHARS) + "…"
                : summary;
        CACHE.put(sessionId, value);
    }

    /** 读取会话最近一轮的工具执行摘要；无则返回 null */
    public static String get(String sessionId) {
        return sessionId == null ? null : CACHE.get(sessionId);
    }

    /** 移除会话摘要（会话真正结束/解绑时的显式入口；日常靠容量兜底） */
    public static void remove(String sessionId) {
        if (sessionId != null) {
            CACHE.remove(sessionId);
        }
    }

    /** 固定容量的 LRU（access-order，超上限淘汰最久未访问的会话） */
    private static final class BoundedLruMap extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        private final int maxSize;

        private BoundedLruMap(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > maxSize;
        }
    }
}

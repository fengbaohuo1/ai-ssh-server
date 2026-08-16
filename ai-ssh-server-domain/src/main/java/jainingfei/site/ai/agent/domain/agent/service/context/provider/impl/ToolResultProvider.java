package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具结果上下文提供者（order=40）。
 *
 * <p>按会话缓存工具执行结果，生成"工具执行摘要"注入 Prompt，
 * 让模型在多轮工具调用后仍能全局回顾"之前执行过什么、结果如何"。
 * 与显式循环内的 lastToolResultText（原始结果，只活一轮）互补：
 * 本 Provider 是会话级缓存，跨轮次、跨对话保留。
 *
 * <p>摘要策略：条目 &lt;= 5 时逐条拼接（结果截断 100 字）；
 * 条目 &gt; 5 时模板化压缩（总数 + 最近 5 条，截断 80 字）。
 * 缓存是"懒加载"的——provide 时缓存命中直接返回，pushResult 写入新结果才失效。
 */
@Component
public class ToolResultProvider implements ContextProvider {

    private static final int MAX_ENTRIES_PER_SESSION = 50;

    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "tool-result";
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) {
            return result;
        }
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }

    /** 写入一条工具执行结果，并使摘要缓存失效 */
    public void pushResult(String sessionId, String toolName, String result) {
        List<ToolResultEntry> entries = results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        entries.add(new ToolResultEntry(toolName, result));
        while (entries.size() > MAX_ENTRIES_PER_SESSION) {
            entries.remove(0);
        }
        summaryCache.remove(sessionId);
    }

    /** 清理指定会话的工具结果缓存（对话结束后调用，防旧结果污染下一轮） */
    public void clear(String sessionId) {
        if (sessionId != null) {
            results.remove(sessionId);
            summaryCache.remove(sessionId);
        }
    }

    private String generateSummary(List<ToolResultEntry> entries) {
        if (entries.size() <= 5) {
            return entries.stream()
                    .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                    .collect(Collectors.joining("\n"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近执行了 ").append(entries.size()).append(" 个工具调用:\n");
        List<ToolResultEntry> recent = entries.subList(entries.size() - 5, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
                    .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }
}

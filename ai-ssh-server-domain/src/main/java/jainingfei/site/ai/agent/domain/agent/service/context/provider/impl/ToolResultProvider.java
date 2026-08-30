package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    public void pushResult(String sessionId, String toolName, String command, boolean success, String result) {
        List<ToolResultEntry> entries = results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        entries.add(new ToolResultEntry(toolName, command, success, result));
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

    /** 查询指定会话已缓存的工具结果条数（供增量注入水位对比） */
    public int count(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        List<ToolResultEntry> entries = results.get(sessionId);
        return entries == null ? 0 : entries.size();
    }

    /**
     * 查询自 fromIndex 下标以来的工具执行时间线（每条一行："-$ cmd ✓/✗"）。
     *
     * <p>供增量前缀注入——显式循环下工具执行摘要在后续轮次不注入，
     * 时间线以增量方式补上长程轨迹：每轮追加一轮的执行流水，
     * 配合会话历史累积，模型能看到跨轮的"做过什么、成败如何"。
     */
    public List<String> timelineSince(String sessionId, int fromIndex) {
        if (sessionId == null) {
            return List.of();
        }
        List<ToolResultEntry> entries = results.get(sessionId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        // 快照后取增量，避免遍历期间新条目加入引发并发修改
        List<ToolResultEntry> snapshot = new ArrayList<>(entries);
        int from = Math.max(0, fromIndex);
        if (from >= snapshot.size()) {
            return List.of();
        }
        return snapshot.subList(from, snapshot.size()).stream()
                .map(e -> "- $ " + truncate(e.getCommand() != null ? e.getCommand() : e.getToolName(), 80)
                        + (e.isSuccess() ? " ✓" : " ✗"))
                .collect(Collectors.toList());
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
        /** 执行的命令（时间线展示用） */
        private String command;
        /** 执行是否成功（时间线 ✓/✗ 标记） */
        private boolean success;
        private String result;
    }
}

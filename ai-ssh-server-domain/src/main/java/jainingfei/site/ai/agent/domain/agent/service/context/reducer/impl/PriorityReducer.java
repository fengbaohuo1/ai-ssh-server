package jainingfei.site.ai.agent.domain.agent.service.context.reducer.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageGroupUtil;
import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageGroupUtil.MessageGroup;
import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 优先级裁剪器——按消息组推断优先级，预算不足时优先丢弃"不重要"的消息组。
 *
 * <p>优先级规则（inferPriority）：
 * <pre>
 *   CRITICAL : tool 结果含 error/failed/exception/permission denied
 *              （错误必须让模型看到，否则会在同一个坑反复失败）
 *   HIGH     : system 消息；user 消息含 /、.conf、.yml、.properties；
 *              assistant 消息包含 tool_calls
 *   LOW      : assistant 回复 &gt; 5000 字符（多为冗长输出，可丢）
 *   MEDIUM   : 其余消息
 * </pre>
 *
 * <p>裁剪过程：
 * <pre>
 *   1. 消息切分为消息组（工具调用链成组，普通消息单独成组）
 *   2. 无条件保留最近 2 个消息组，保证当前轮上下文完整
 *   3. 其余历史组按优先级从高到低、同级按时间从近到远回填（预算内）
 *   4. 最终按原始顺序输出，保证时间线不乱序
 * </pre>
 *
 * <p>核心目标不是"越新越好"，而是"关键历史不要丢"——
 * 比滑动窗口更适合保留错误、配置路径、tool 调用入口这类高价值信息。
 */
@Component
public class PriorityReducer implements MessageReducer {

    private enum Priority {
        CRITICAL(3), HIGH(2), MEDIUM(1), LOW(0);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }
    }

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = MessageGroupUtil.groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        // 1. 至少保留最近 2 个消息组，优先保障当前轮对话和最近一次工具调用链路完整
        int minKeep = Math.min(2, groups.size());
        int startIndex = groups.size() - minKeep;
        List<MessageGroup> kept = new ArrayList<>(groups.subList(startIndex, groups.size()));
        int usedTokens = kept.stream().mapToInt(MessageGroupUtil::estimateTokens).sum();

        // 2. 更早的历史组按优先级回填：高优先级优先，同级越新越优先
        List<MessageGroup> candidates = new ArrayList<>(groups.subList(0, startIndex));
        candidates.sort(Comparator
                .comparing((MessageGroup g) -> inferPriority(g).weight).reversed()
                .thenComparing(MessageGroup::startIndex, Comparator.reverseOrder()));

        for (MessageGroup group : candidates) {
            int groupTokens = MessageGroupUtil.estimateTokens(group);
            if (usedTokens + groupTokens <= tokenBudget) {
                kept.add(group);
                usedTokens += groupTokens;
            }
        }

        // 3. 输出恢复原始时间顺序
        return kept.stream()
                .sorted(Comparator.comparingInt(MessageGroup::startIndex))
                .flatMap(g -> g.messages().stream())
                .collect(Collectors.toList());
    }

    /** 推断消息组优先级 */
    private Priority inferPriority(MessageGroup group) {
        for (Map<String, Object> m : group.messages()) {
            String role = String.valueOf(m.getOrDefault("role", ""));
            Object contentObj = m.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            Object toolCalls = m.get("tool_calls");

            if ("tool".equals(role)) {
                String lower = content.toLowerCase();
                if (lower.contains("error") || lower.contains("failed") || lower.contains("exception")
                        || lower.contains("permission denied")) {
                    return Priority.CRITICAL;
                }
            } else if ("system".equals(role)) {
                return Priority.HIGH;
            } else if ("user".equals(role)) {
                if (content.contains("/") || content.contains(".conf")
                        || content.contains(".yml") || content.contains(".properties")) {
                    return Priority.HIGH;
                }
            } else if ("assistant".equals(role)) {
                if (toolCalls instanceof List<?> list && !list.isEmpty()) {
                    return Priority.HIGH;
                }
                if (content.length() > 5000) {
                    return Priority.LOW;
                }
            }
        }
        return Priority.MEDIUM;
    }
}

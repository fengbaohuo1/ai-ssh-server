package jainingfei.site.ai.agent.domain.agent.service.context.reducer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息分组工具——裁剪器共享的"可裁剪单元"切分逻辑。
 *
 * <p>为什么按"消息组"而不是"单条消息"裁剪：
 * "assistant 发起工具调用 + tool 返回执行结果"在语义上是一件事，
 * 拆开裁剪会让模型只看到"调用了工具"却看不到结果。
 * PriorityReducer / SlidingWindowReducer / HybridReducer 必须对
 * "完整上下文单元"的理解一致，因此分组逻辑收敛到这里。
 */
public final class MessageGroupUtil {

    private MessageGroupUtil() {
    }

    /** 消息组：组内消息按原始顺序保存，startIndex 是该组在原始列表中的起始下标 */
    public record MessageGroup(List<Map<String, Object>> messages, int startIndex) {
    }

    /**
     * 将原始消息切分为消息组。
     *
     * <p>规则：assistant 且带非空 tool_calls 的消息开启一组，
     * 后续所有 tool_call_id 匹配的 tool 结果并入同一组；
     * 其余消息单独成组。
     */
    public static List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                List<Map<String, Object>> grouped = new ArrayList<>();
                grouped.add(message);

                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        grouped.add(candidate);
                        next++;
                        continue;
                    }
                    break;
                }
                groups.add(new MessageGroup(grouped, index));
                index = next;
                continue;
            }
            groups.add(new MessageGroup(List.of(message), index));
            index++;
        }
        return groups;
    }

    /** 估算单个消息组的 token 消耗（content.length()/2，经验估算，给裁剪器"够用的体积感知"） */
    public static int estimateTokens(MessageGroup group) {
        return group.messages().stream()
                .mapToInt(m -> stringValue(m.get("content")).length() / 2)
                .sum();
    }

    private static boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
    }

    private static boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new HashSet<>();
        Object toolCalls = message.get("tool_calls");
        if (!(toolCalls instanceof List<?> list)) {
            return toolCallIds;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> call) {
                Object id = ((Map<String, Object>) call).get("id");
                if (id != null) {
                    toolCallIds.add(String.valueOf(id));
                }
            }
        }
        return toolCallIds;
    }

    /** 判断一条消息是否属于当前工具调用组的 tool result（兼容 tool_call_id 键名） */
    private static boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        if (!"tool".equals(stringValue(message.get("role")))) {
            return false;
        }
        String toolCallId = stringValue(message.get("tool_call_id"));
        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

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
 * 滑动窗口裁剪器——保留"最近"的消息组。
 *
 * <p>从新到旧按组装入窗口，受"窗口组数（20）+ token 预算"双重限制，
 * 任一超限即停止（不跳过当前组继续找更早的组——目标是保留
 * "最近的一段连续上下文"，而不是在旧历史里做离散挑选）。
 *
 * <p>时效性保障：最近的完整对话轮次和工具调用链对当前推理最重要。
 */
@Component
public class SlidingWindowReducer implements MessageReducer {

    private static final int DEFAULT_WINDOW_GROUP_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = MessageGroupUtil.groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> window = new ArrayList<>();
        int usedTokens = 0;

        // 从最新消息组向前回收，优先保留最近轮次的完整上下文
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroup group = groups.get(i);
            int groupTokens = MessageGroupUtil.estimateTokens(group);
            if (window.size() >= DEFAULT_WINDOW_GROUP_SIZE || usedTokens + groupTokens > tokenBudget) {
                break;
            }
            window.add(0, group);
            usedTokens += groupTokens;
        }

        return window.stream()
                .sorted(Comparator.comparingInt(MessageGroup::startIndex))
                .flatMap(g -> g.messages().stream())
                .collect(Collectors.toList());
    }
}

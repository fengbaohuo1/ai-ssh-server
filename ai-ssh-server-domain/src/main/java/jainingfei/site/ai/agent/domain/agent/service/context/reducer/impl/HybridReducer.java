package jainingfei.site.ai.agent.domain.agent.service.context.reducer.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageGroupUtil;
import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageGroupUtil.MessageGroup;
import jainingfei.site.ai.agent.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合裁剪器——ChatContextService 实际使用的裁剪策略。
 *
 * <p>组合两种裁剪器的优点：PriorityReducer 保证"重要的不丢"，
 * SlidingWindowReducer 保证"新的不丢"。以 PriorityReducer 为主结果，
 * 补充 SlidingWindowReducer 中近期且有价值的缺失消息，最后统一保底
 * 最近 2 个完整消息组，兼顾重要性、时效性和消息配对完整性。
 *
 * <pre>
 *   messages ──+--&gt; PriorityReducer.reduce()      --&gt; 主保留集合 A
 *              |
 *              +--&gt; SlidingWindowReducer.reduce() --&gt; 候选集合 B
 *                          |
 *                          v
 *             keep = A + (B 中尚未保留的消息，按原顺序补充)
 *                          |
 *                          v
 *                 保底：强制加入最近 2 个完整消息组
 *                          |
 *                          v
 *                   按原顺序输出最终消息
 * </pre>
 */
@Component
public class HybridReducer implements MessageReducer {

    @Resource
    private PriorityReducer priorityReducer;

    @Resource
    private SlidingWindowReducer slidingWindowReducer;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> priorityResult = priorityReducer.reduce(messages, tokenBudget);
        List<Map<String, Object>> slidingResult = slidingWindowReducer.reduce(messages, tokenBudget);

        // 第一步：PriorityReducer 的结果作为"重要历史主集合"
        Set<Integer> keepIndices = new LinkedHashSet<>(indexSet(priorityResult, messages));

        // 第二步：用最近窗口结果补充优先级裁剪中未保留的消息，强化当前轮连续性
        keepIndices.addAll(indexSet(slidingResult, messages));

        // 第三步：统一保底最近 2 个完整消息组，避免把工具调用链截断
        List<MessageGroup> groups = MessageGroupUtil.groupMessages(messages);
        int minKeepGroups = Math.min(2, groups.size());
        IdentityHashMap<Map<String, Object>, Integer> identityIndex = identityIndex(messages);
        for (int i = groups.size() - minKeepGroups; i < groups.size(); i++) {
            for (Map<String, Object> m : groups.get(i).messages()) {
                Integer idx = identityIndex.get(m);
                if (idx != null) {
                    keepIndices.add(idx);
                }
            }
        }

        // 第四步：按原始顺序输出
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) {
                result.add(messages.get(i));
            }
        }
        return result;
    }

    /** 建立原始消息 → 下标的引用身份映射（内容相同的不同消息也能区分） */
    private IdentityHashMap<Map<String, Object>, Integer> identityIndex(List<Map<String, Object>> original) {
        IdentityHashMap<Map<String, Object>, Integer> map = new IdentityHashMap<>();
        for (int i = 0; i < original.size(); i++) {
            map.put(original.get(i), i);
        }
        return map;
    }

    /** 将裁剪结果映射回原始消息的下标集合（按引用身份匹配） */
    private Set<Integer> indexSet(List<Map<String, Object>> reduced, List<Map<String, Object>> original) {
        Set<Integer> indices = new LinkedHashSet<>();
        IdentityHashMap<Map<String, Object>, Integer> identityIndex = identityIndex(original);
        for (Map<String, Object> m : reduced) {
            Integer idx = identityIndex.get(m);
            if (idx != null) {
                indices.add(idx);
            }
        }
        return indices;
    }
}

package jainingfei.site.ai.agent.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

/**
 * 消息历史裁剪器接口。
 *
 * <p>在 token 预算内裁剪消息历史，保证送入上下文的每条消息都有价值。
 * 两个实现各有侧重，由 HybridReducer 组合使用：
 * <ul>
 *   <li>PriorityReducer —— 按重要性保留（错误、关键指令、工具链不丢）</li>
 *   <li>SlidingWindowReducer —— 按时效性保留（最近连续上下文不丢）</li>
 * </ul>
 */
public interface MessageReducer {

    /**
     * 裁剪消息历史。
     *
     * @param messages    原始消息列表（时间正序）
     * @param tokenBudget token 预算，裁剪后的总 token 估算不超过该值
     * @return 裁剪后的消息列表（保持原始时间顺序）
     */
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);
}

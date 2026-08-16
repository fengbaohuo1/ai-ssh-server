package jainingfei.site.ai.agent.domain.agent.service.context.provider;

import java.util.List;
import java.util.Map;

/**
 * 上下文采集器（Provider）接口——可插拔的上下文来源。
 *
 * <p>每个实现负责采集一类上下文（终端环境 / 当前任务 / 里程碑 / 工具摘要），
 * 由 {@code ChatContextService} 按 {@link #getOrder()} 顺序聚合为 PromptContextVO。
 * 新增上下文来源只需新增实现类并加 {@code @Component}，无需改动调用方（开闭原则）。
 *
 * <pre>
 *                     ChatContextService
 *                     （注入 List&lt;ContextProvider&gt;，按 order 排序）
 *                           |
 *   +----------+-----------+-----------+----------+
 *   |          |           |           |
 *   v          v           v           v
 * Terminal   Task     Milestone  ToolResult    （order: 10/20/30/40）
 * State      Provider Provider   Provider
 * (SSH环境)  (当前任务) (关键事件)  (工具摘要)
 *   |          |           |           |
 *   +----------+-----------+-----------+
 *                          |
 *                          v
 *              合并 Map --&gt; PromptContextVO
 * </pre>
 */
public interface ContextProvider {

    /** 提供者名称 */
    String getName();

    /** 执行顺序（小的先执行） */
    int getOrder();

    /** 是否启用 */
    boolean enabled();

    /**
     * 采集上下文。
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史（业务侧，已裁剪）
     * @return 上下文键值对（如 osInfo、toolResultSummary），允许为空
     */
    Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                List<Map<String, Object>> messageHistory);
}

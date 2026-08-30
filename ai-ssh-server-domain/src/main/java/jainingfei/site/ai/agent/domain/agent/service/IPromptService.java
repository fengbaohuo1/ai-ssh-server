package jainingfei.site.ai.agent.domain.agent.service;

import java.util.List;
import java.util.Map;

/**
 * 提示词领域服务接口——封装动态 Prompt 构建、里程碑追踪等能力。
 *
 * <p>Case 层只依赖此接口，不直接接触 DynamicPromptBuilder / MilestoneTracker /
 * ChatContextService 等内部组件。
 */
public interface IPromptService {

    /**
     * 检测并记录里程碑事件（用户纠偏、任务切换、错误等）。
     *
     * @param sessionId 对话会话 ID
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容
     */
    void detectAndRecordMilestone(String sessionId, String role, String content);

    /**
     * 构建注入了动态上下文的用户消息（富化消息）。
     *
     * <p>内部完成：
     * <ol>
     *   <li>通过 IChatContextService 聚合各 ContextProvider（环境 / 任务 / 里程碑 / 工具摘要）</li>
     *   <li>构建 PromptContextVO 并生成消息前缀</li>
     *   <li>将前缀与原始用户消息拼接</li>
     * </ol>
     *
     * @param userMessage        原始用户消息
     * @param sessionId          对话会话 ID
     * @param userId             用户 ID
     * @param terminalSessionId  SSH 终端会话 ID（即 connectionId，可为 null）
     * @param recentCommands     最近执行的命令列表
     * @param messageHistory     业务侧消息历史（已裁剪）
     * @param intentLabel        意图标签（由意图识别系统提供，可为 null）
     * @return 注入了动态上下文的用户消息（前缀 + "\n---\n" + 原始消息）
     */
    String buildEnrichedMessage(String userMessage, String sessionId, String userId,
                                String terminalSessionId, List<String> recentCommands,
                                List<Map<String, Object>> messageHistory, String intentLabel);

    /**
     * 构建注入了动态上下文的用户消息（向后兼容重载，无意图标签时使用）。
     */
    default String buildEnrichedMessage(String userMessage, String sessionId, String userId,
                                        String terminalSessionId, List<String> recentCommands,
                                        List<Map<String, Object>> messageHistory) {
        return buildEnrichedMessage(userMessage, sessionId, userId, terminalSessionId,
                recentCommands, messageHistory, null);
    }

    /**
     * 构建增量富化前缀（显式循环第 2 轮起使用）。
     *
     * <p>第 1 轮已通过 {@link #buildEnrichedMessage} 全量注入环境信息、最近命令与里程碑；
     * 后续轮次若再全量注入，前缀会与 ADK 会话历史中已累积的旧前缀重复出现，浪费 token。
     * 本方法只注入"新增"内容：
     * <ul>
     *   <li>recentCommands 中自 sinceCommandCount 起的新命令（第 1 轮已注入前 N 条）</li>
     *   <li>时间戳晚于 sinceMilestoneTs 的新里程碑（水位线由调用方在每次注入后更新）</li>
     *   <li>自 sinceToolResultCount 起的工具执行时间线（一行式流水，弥补长程轨迹盲区）</li>
     * </ul>
     *
     * @param sessionId             对话会话 ID
     * @param sinceMilestoneTs      已注入里程碑的时间戳水位线，只返回晚于该值的里程碑
     * @param recentCommands        最近执行的命令列表（全量，内部按下标截取增量）
     * @param sinceCommandCount     已注入的命令条数，只返回该下标之后的新命令
     * @param sinceToolResultCount  已注入的工具结果条数水位线
     * @return 增量前缀，无新增内容时返回空串（调用方此时直接用原始用户消息）
     */
    String buildIncrementalPrefix(String sessionId, long sinceMilestoneTs,
                                  List<String> recentCommands, int sinceCommandCount,
                                  int sinceToolResultCount);

    /**
     * 清除指定会话的里程碑记录。
     *
     * @param sessionId 对话会话 ID
     */
    void clearMilestones(String sessionId);
}

package jainingfei.site.ai.agent.domain.agent.service;

import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.PromptContextVO;

import java.util.List;
import java.util.Map;

/**
 * 上下文管理领域服务接口——ReAct 对话的"上下文中枢"。
 *
 * <p>对上（PromptService / Case 层）提供四个能力：
 * <ul>
 *   <li>{@link #buildPromptContext} —— 聚合所有 ContextProvider 的输出 → PromptContextVO</li>
 *   <li>{@link #trimHistory} —— 在 token 预算内裁剪消息历史（默认 8000）</li>
 *   <li>{@link #pushToolResult} —— 接收工具执行结果，供生成工具摘要</li>
 *   <li>{@link #clearSessionContext} —— 清理指定会话的上下文缓存</li>
 * </ul>
 */
public interface IChatContextService {

    /**
     * 聚合所有已启用的 ContextProvider，构建 PromptContextVO。
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    业务侧消息历史（已裁剪）
     * @return 聚合完成的 PromptContextVO
     */
    PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory);

    /**
     * 按 token 预算裁剪业务侧消息历史。
     *
     * @param history     原始消息历史
     * @param tokenBudget token 预算；小于等于 0 时回退到默认值 8000
     * @return 裁剪后的消息历史
     */
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);

    /**
     * 推送一条工具执行结果到会话级缓存（ToolResultProvider），
     * 供下一轮 buildPromptContext 生成工具摘要、增量动态段生成执行时间线。
     *
     * @param sessionId 当前会话 ID
     * @param toolName  工具名称
     * @param command   执行的命令（时间线展示用，可为 null）
     * @param success   执行是否成功（时间线 ✓/✗ 标记）
     * @param result    工具执行结果
     */
    void pushToolResult(String sessionId, String toolName, String command, boolean success, String result);

    /**
     * 查询指定会话已缓存的工具结果条数（增量注入水位对比用）。
     */
    int getToolResultCount(String sessionId);

    /**
     * 查询自 fromIndex 下标以来的工具执行时间线（每条一行："-$ cmd ✓/✗"）。
     * 供增量动态段注入，弥补"工具执行摘要只覆盖最近几条"的长程轨迹盲区。
     */
    java.util.List<String> getToolResultTimeline(String sessionId, int fromIndex);

    /**
     * 清理指定会话的上下文缓存（对话结束后调用，防旧结果污染下一轮）。
     *
     * @param sessionId 当前会话 ID
     */
    void clearSessionContext(String sessionId);

    // ═══════════════════════════════════════════════════════════════
    //  会话级运行状态（V3 托管循环：跨"用户回合"持久、回合内供工具/反馈读写）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 记录一条最近执行的命令（FIFO，上限 20，跨回合保留——回合级富化 [最近执行的命令] 的数据源）。
     */
    void pushRecentCommand(String sessionId, String command);

    /** 查询会话最近执行的命令（按时间正序）。 */
    java.util.List<String> getRecentCommands(String sessionId);

    /**
     * 保存会话当前意图识别结果（回合开始 classify 写入；回合中工具反馈可重分类覆盖）。
     */
    void setSessionIntent(String sessionId, jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO intent);

    /** 读取会话当前意图识别结果（无则 null）。 */
    jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO getSessionIntent(String sessionId);

    /**
     * 置位意图修正提示（反馈回路重分类后）——下一回合入口富化消费后清空（一次性注入防重复）。
     */
    void setPendingIntentCorrection(String sessionId, String correction);

    /** 取走并清除待注入的意图修正提示（无则 null）。 */
    String consumePendingIntentCorrection(String sessionId);
}

package jainingfei.site.ai.agent.domain.agent.service;

import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;

/**
 * 意图识别服务接口
 * <p>
 * 意图识别子系统的对外门面，由 {@code IntentService} 实现。能力分四组：
 * <ol>
 *   <li>分类：{@link #classify} —— 规则→LLM 级联识别用户意图</li>
 *   <li>反馈回路：{@link #reportFeedback} —— 工具执行后回报，失败可触发重分类</li>
 *   <li>任务态读写：{@link #getTaskState}/{@link #updateTaskState} —— 缓存载体</li>
 *   <li>任务步进状态机（V3.2 回合语义，写路径三入口）：
 *       {@link #syncTaskStateAfterClassification}（AiCallNode 分类后）、
 *       {@link #updateTaskStateAfterFeedback}（ToolCallNode 每工具结果）、
 *       {@link #markTaskCompleted}/{@link #markTaskInterrupted}（LoopDecisionNode 回合收口）</li>
 * </ol>
 * 配置由调用方通过 {@link #configure} 注入 Agent 的 API，使意图识别复用智能体自己的模型。
 */
public interface IIntentService {

    /**
     * 对用户消息进行意图分类
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param message   用户消息
     * @return 意图识别结果
     */
    IntentResultVO classify(String sessionId, String userId, String message);

    /**
     * 反馈回路：下游节点执行工具后回报结果。
     * <p>
     * 当 success=false 且当前意图置信度不高时，IntentService 可决定重分类，
     * 返回新的意图结果；若无需重分类则返回 null。
     *
     * @param sessionId  会话 ID
     * @param lastIntent 上一次识别的意图
     * @param success    本轮工具执行是否成功
     * @param toolResult 工具执行结果文本（用于判断是否需要重分类）
     * @return 重分类后的新意图，或 null 表示维持原意图
     */
    IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent,
                                  boolean success, String toolResult);

    /**
     * 获取当前会话的任务态
     */
    TaskStateVO getTaskState(String sessionId);

    /**
     * 更新当前会话的任务态
     */
    void updateTaskState(String sessionId, TaskStateVO taskState);

    /**
     * 分类后同步任务态（回合入口，AiCallNode 调用）。
     * <p>
     * CONTINUE 续接 / UNKNOWN·CHAT 跳过 / 业务意图重建或复用，详见实现类 javadoc。
     *
     * @param sessionId       会话 ID
     * @param lastUserMessage 最新用户消息（作为新任务的描述/首步）
     * @param intentResult    本轮意图识别结果
     * @return 同步后的任务态（可能为 null）
     */
    TaskStateVO syncTaskStateAfterClassification(String sessionId, String lastUserMessage,
                                                 IntentResultVO intentResult);

    /**
     * 反馈后更新任务态（每个工具结果打点，ToolCallNode 调用）。
     * <p>
     * 成功追加留痕并清失败标记、失败置位、重分类为业务意图则替换根意图；
     * 不在本方法推进 completed（由回合收口统一判定）。
     *
     * @param sessionId     会话 ID
     * @param success       当前工具结果是否成功
     * @param reclassified  反馈回路重分类结果（可为 null）
     * @param actionSummary 成功动作的摘要（调用方已截断），追加到 steps 留痕
     * @return 更新后的任务态；无任务态返回 null
     */
    TaskStateVO updateTaskStateAfterFeedback(String sessionId, boolean success,
                                             IntentResultVO reclassified, String actionSummary);

    /**
     * 回合正常收口 → 任务完成归档（LoopDecisionNode 调用）。
     */
    void markTaskCompleted(String sessionId);

    /**
     * 回合异常中断 → 任务保持可续接并打失败标记（LoopDecisionNode 调用）。
     */
    void markTaskInterrupted(String sessionId);

    /**
     * 冷启动恢复任务态（P1）：内存无任务态且 DB（chat_task_state）有记录时写回内存。
     * 由 RootNode 在会话重建后调用，早于 classify（CONTINUE 命中）与 TaskProvider。
     */
    void restoreSessionTaskState(String sessionId);

    /**
     * 注入智能体的 API 配置，供 LLM 意图分类器复用。
     * <p>
     * 由调用方（如 AiCallNode）在分类前传入 Agent 装配好的 baseUrl/apiKey/模型名，
     * 使得意图识别不再需要单独配置模型，而是使用各智能体自己的配置。
     * <p>
     * 注意：spring-ai 2.x 已移除 OpenAiApi 类，故此处直接传三段配置字符串，
     * 由 LLMIntentClassifier 内部用 OpenAiChatOptions 构建独立 ChatModel。
     *
     * @param baseUrl   Agent 装配链路中的 API baseUrl
     * @param apiKey    Agent 装配链路中的 API key
     * @param modelName Agent 配置的模型名
     */
    void configure(String baseUrl, String apiKey, String modelName);

}

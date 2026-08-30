package jainingfei.site.ai.agent.domain.agent.service;

import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆领域服务接口——从对话流中提取结构化记忆，并支持关键词召回。
 *
 * <p>方法分两组：
 * <ul>
 *   <li>{@code record*} —— 纯记忆提取（消息流经时实时调用）</li>
 *   <li>{@code save*} / {@code getRecentMessages} —— case 层统一入口：消息落库 + 记忆提取闭环</li>
 * </ul>
 *
 * <p>四种记忆类型（置信度由来源可靠性决定）：
 * <table border="1">
 * <tr><th>类型</th><th>触发条件</th><th>key 策略</th><th>置信度</th></tr>
 * <tr><td>USER_PREFERENCE</td><td>用户消息含偏好词（"以后""默认""记住"等）</td><td>sha1("pref:"+内容)</td><td>0.85</td></tr>
 * <tr><td>ENVIRONMENT_FACT</td><td>工具输出匹配 OS 关键词</td><td>"os:ubuntu"</td><td>0.70</td></tr>
 * <tr><td>SOFTWARE_FACT</td><td>工具输出匹配软件版本模式</td><td>"nginx:version"</td><td>0.78~0.82</td></tr>
 * <tr><td>TROUBLESHOOTING_CASE</td><td>工具失败 / 助手含"结论""根因"等</td><td>sha1(内容)</td><td>0.68~0.72</td></tr>
 * </table>
 *
 * <p>设计原则：零外部依赖（不需要 Embedding API、不需要向量数据库），
 * 纯 MySQL + 关键词匹配——SSH 运维场景关键词天然具有高区分度。
 */
public interface ILongTermMemoryService {

    /**
     * 从用户消息中提取偏好类记忆（含"以后/默认/记住"等偏好词才提取）
     *
     * @param intentLabel 意图标签，拼入关键词提高后续召回命中率，可为 null
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * 从工具输出中提取环境事实/软件版本/失败信号记忆
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

    /**
     * 从助手回复中提取排查结论类记忆（含"结论/原因/建议/修复/根因"才提取）
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    /**
     * 用户消息落库（仅首轮）+ 偏好记忆提取，case 层统一入口
     */
    void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound);

    /**
     * 助手回复落库 + 结论记忆提取，case 层统一入口
     */
    void saveAssistantMessage(String userId, String sessionId, String assistantContent);

    /**
     * 工具结果落库（priority=HIGH）+ 环境事实/软件版本/失败信号记忆提取，case 层统一入口
     */
    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success);

    /**
     * 会话历史查询（冷启动恢复用）：委托仓储取最近 N 条消息（时间正序）
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * 召回与查询相关的长期记忆：粗筛（最近 120 条）+ 精排（关键词重叠打分）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * 构建记忆摘要（每行一条："- [类型] 内容"），供 Prompt 注入
     */
    String buildMemorySummary(String userId, String query, int limit);

    /**
     * 失败内容启发式判断——输出含 error/failed/permission denied 等高信号失败特征。
     * 供 ToolCallNode 在工具结果无显式失败标志时兜底判断。
     */
    boolean isFailureContent(String content);
}

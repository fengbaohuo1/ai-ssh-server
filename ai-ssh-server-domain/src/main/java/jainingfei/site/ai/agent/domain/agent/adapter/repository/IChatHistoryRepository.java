package jainingfei.site.ai.agent.domain.agent.adapter.repository;

import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;

import java.util.List;

/**
 * 对话历史仓储接口——chat_session / chat_message / chat_milestone 三张表的领域出口。
 *
 * <p>设计原则：调用方（领域服务/节点）只依赖本接口；所有写入路径都是
 * "旁路写入"语义——由调用方 try-catch 包裹，DB 失败不影响 Agent 主流程。
 */
public interface IChatHistoryRepository {

    /**
     * 保存会话元数据（id 已存在时跳过，支持服务重启后客户端带回旧 sessionId 的场景）
     */
    void saveSession(ChatSessionEntity session);

    /**
     * 保存一条消息，并原子递增所属会话的 message_count
     */
    void saveMessage(ChatMessageEntity message);

    /**
     * 查询会话最近 N 条消息（时间正序返回）
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * 保存一条里程碑
     */
    void saveMilestone(String sessionId, MilestoneVO milestoneVO);

    /**
     * 查询会话最近 N 条里程碑（时间正序返回）
     */
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);

    /**
     * 查询用户在某智能体下的会话列表（按更新时间倒序）
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 查询会话消息列表（时间正序）
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);
}

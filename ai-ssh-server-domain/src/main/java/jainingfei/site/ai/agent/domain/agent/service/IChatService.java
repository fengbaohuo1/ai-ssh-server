package jainingfei.site.ai.agent.domain.agent.service;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

public interface IChatService {
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    /**
     * 创建/恢复会话（2-8 会话连续性）：客户端带回旧 sessionId 时复用或以该 id 重建 ADK 会话，
     * 实现服务重启后的冷启动恢复；同时旁路落库 chat_session 元数据。
     *
     * @param clientSessionId 客户端持有的会话 ID，可为 null（首次创建）
     */
    String createSession(String agentId, String userId, String clientSessionId);

    /**
     * 查询用户在某智能体下的会话列表（按更新时间倒序）
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 查询会话消息列表（时间正序）
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 流式对话（携带 SSH 终端会话 ID）
     *
     * @param agentId           智能体 ID
     * @param userId            用户 ID
     * @param sessionId         对话会话 ID
     * @param message           消息内容
     * @param terminalSessionId SSH 终端会话 ID（用于工具调用）
     * @return 事件流
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    void clearHistory(String sessionId);
}

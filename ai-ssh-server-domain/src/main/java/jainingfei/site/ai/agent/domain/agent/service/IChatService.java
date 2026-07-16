package jainingfei.site.ai.agent.domain.agent.service;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

public interface IChatService {
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

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

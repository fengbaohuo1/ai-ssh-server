package jainingfei.site.ai.agent.api;



import jainingfei.site.ai.agent.api.dto.*;
import jainingfei.site.ai.agent.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    SseEmitter chatStream(ChatRequestDTO requestDTO);

    /**
     * 查询用户在某智能体下的会话列表（2-8 会话历史，按更新时间倒序）
     */
    Response<List<ChatSessionResponseDTO>> querySessionList(String agentId, String userId, Integer limit);

    /**
     * 查询会话消息列表（2-8 会话历史回放，时间正序）
     */
    Response<List<ChatMessageResponseDTO>> queryMessageList(String sessionId, Integer limit);

}

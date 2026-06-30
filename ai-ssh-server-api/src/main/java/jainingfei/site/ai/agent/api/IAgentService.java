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

}

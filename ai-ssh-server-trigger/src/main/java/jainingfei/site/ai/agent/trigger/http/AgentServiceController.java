package jainingfei.site.ai.agent.trigger.http;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.IAgentService;
import jainingfei.site.ai.agent.api.dto.*;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.AgentStepBuffer;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private IChatService chatService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            // 清空步骤缓冲，确保本次对话的步骤记录干净
            AgentStepBuffer.getInstance().clear();

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            // 收集 Agent 动作步骤
            String steps = AgentStepBuffer.getInstance().drain();

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));
            if (steps != null && !steps.isEmpty()) {
                responseDTO.setSteps(steps);
            }

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        AgentStepBuffer buffer = AgentStepBuffer.getInstance();
        buffer.clear();

        // 后台定时器：每 80ms 轮询一次步骤缓冲区，逐个 chunk 推给前端
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "step-push");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String chunk;
                while ((chunk = buffer.drainOne()) != null) {
                    StringBuilder json = new StringBuilder("{\"v\":\"\",\"s\":");
                    json.append(chunk);
                    json.append("}");
                    emitter.send(SseEmitter.event().data(json.toString()));
                }
            } catch (Exception ignored) {
            }
        }, 0, 80, TimeUnit.MILLISECONDS);

        try {
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getMessage());

            chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(),
                            requestDTO.getSessionId(), requestDTO.getMessage())
                    .subscribe(
                            event -> {
                                try {
                                    String content = event.stringifyContent();
                                    String steps = buffer.drain();
                                    if (content != null && !content.isEmpty() || !steps.isEmpty()) {
                                        StringBuilder json = new StringBuilder("{\"v\":");
                                        json.append(toJsonString(content != null ? content : ""));
                                        if (!steps.isEmpty()) {
                                            json.append(",\"s\":[").append(steps).append("]");
                                        }
                                        json.append("}");
                                        emitter.send(SseEmitter.event().data(json.toString()));
                                    }
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                    emitter.completeWithError(e);
                                }
                            },
                            error -> {
                                scheduler.shutdownNow();
                                emitter.completeWithError(error);
                            },
                            () -> {
                                try {
                                    String chunk;
                                    while ((chunk = buffer.drainOne()) != null) {
                                        StringBuilder json = new StringBuilder("{\"v\":\"\",\"s\":");
                                        json.append(chunk);
                                        json.append("}");
                                        emitter.send(SseEmitter.event().data(json.toString()));
                                    }
                                } catch (Exception ignored) {}
                                scheduler.shutdownNow();
                                emitter.complete();
                            }
                    );
        } catch (Exception e) {
            scheduler.shutdownNow();
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private static String toJsonString(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

}

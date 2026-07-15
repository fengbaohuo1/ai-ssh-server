package jainingfei.site.ai.agent.trigger.http;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.IAgentService;
import jainingfei.site.ai.agent.api.dto.*;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
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

            ChatResult result = processEventStream(
                    requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(result.text());
            if (result.steps() != null && !result.steps().isEmpty()) {
                responseDTO.setSteps(result.steps());
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

    /**
     * 处理 ADK event stream（非流式），分离文本和步骤事件。
     */
    private ChatResult processEventStream(String agentId, String userId, String sessionId, String message) {
        List<String> outputs = chatService.handleMessage(agentId, userId, sessionId, message);
        // 从 stringifyContent 输出中分离文本和步骤
        StringBuilder text = new StringBuilder();
        List<String> steps = new ArrayList<>();
        for (String output : outputs) {
            if (output.startsWith("Function Call:") || output.startsWith("Function Response:")) {
                steps.add(parseStepJson(output));
            } else if (!output.isEmpty()) {
                text.append(output);
            }
        }
        String stepsJson = steps.isEmpty() ? null : "[" + String.join(",", steps) + "]";
        return new ChatResult(text.toString(), stepsJson);
    }

    /** 将 ADK 事件字符串转换为步骤 JSON */
    private String parseStepJson(String eventContent) {
        if (eventContent.startsWith("Function Call:")) {
            String inner = eventContent.substring("Function Call:".length()).trim();
            String name = extractField(inner, "name=");
            String args = extractField(inner, "args=");
            return "{\"t\":\"tool_call\",\"n\":\"" + esc(name) + "\",\"a\":\"" + esc(truncate(args, 200)) + "\"}";
        } else if (eventContent.startsWith("Function Response:")) {
            String inner = eventContent.substring("Function Response:".length()).trim();
            String name = extractField(inner, "name=");
            String response = extractField(inner, "response=");
            return "{\"t\":\"tool_result\",\"n\":\"" + esc(name) + "\",\"r\":\"" + esc(truncate(response, 500)) + "\"}";
        }
        return "{\"t\":\"thinking\"}";
    }

    private String extractField(String s, String fieldPrefix) {
        int start = s.indexOf(fieldPrefix);
        if (start < 0) return "";
        start += fieldPrefix.length();
        int end = s.indexOf(',', start);
        if (end < 0) end = s.indexOf('}', start);
        if (end < 0) end = s.length();
        String val = s.substring(start, end).trim();
        while (val.endsWith("}")) val = val.substring(0, val.length() - 1).trim();
        return val;
    }

    private record ChatResult(String text, String steps) {}

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

        // 发送初始 thinking 步骤
        try {
            emitter.send(SseEmitter.event()
                    .data("{\"v\":\"\",\"s\":{\"t\":\"thinking\"}}"));
        } catch (Exception ignored) {}

        try {
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getMessage());

            chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(),
                            requestDTO.getSessionId(), requestDTO.getMessage())
                    .subscribe(
                            event -> {
                                try {
                                    String step = buildStepFromEvent(event);
                                    String content = extractTextFromEvent(event);
                                    if (step != null && content != null && !content.isEmpty()) {
                                        emitter.send(SseEmitter.event().data(
                                                "{\"v\":" + toJsonString(content)
                                                + ",\"s\":[" + step + "]}"));
                                    } else if (step != null) {
                                        emitter.send(SseEmitter.event().data(
                                                "{\"v\":\"\",\"s\":" + step + "}"));
                                    } else if (content != null && !content.isEmpty()) {
                                        emitter.send(SseEmitter.event().data(
                                                "{\"v\":" + toJsonString(content) + "}"));
                                    }
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                    emitter.completeWithError(e);
                                }
                            },
                            error -> emitter.completeWithError(error),
                            () -> emitter.complete()
                    );
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 从 ADK Event 构建步骤 JSON */
    private String buildStepFromEvent(Event event) {
        var fcs = event.functionCalls();
        var frs = event.functionResponses();
        if (fcs.isEmpty() && frs.isEmpty()) return null;

        if (!fcs.isEmpty()) {
            FunctionCall fc = fcs.get(0);
            String name = fc.name().orElse("unknown");
            String args = fc.args().map(Object::toString).orElse("");
            return "{\"t\":\"tool_call\",\"n\":\"" + esc(name) + "\",\"a\":\""
                    + esc(truncate(args, 200)) + "\"}";
        }
        if (!frs.isEmpty()) {
            FunctionResponse fr = frs.get(0);
            String name = fr.name().orElse("unknown");
            String resp = fr.response().map(Object::toString).orElse("");
            return "{\"t\":\"tool_result\",\"n\":\"" + esc(name) + "\",\"r\":\""
                    + esc(truncate(resp, 500)) + "\"}";
        }
        return null;
    }

    /** 从 ADK Event 提取纯文本内容（排除 function call/response） */
    private String extractTextFromEvent(Event event) {
        return event.content()
                .flatMap(c -> c.parts())
                .map(parts -> parts.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .reduce("", String::concat))
                .orElse("");
    }

    /** JSON 字符串转义 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ");
    }

    /** 截断字符串至 maxLen */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static String toJsonString(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

}

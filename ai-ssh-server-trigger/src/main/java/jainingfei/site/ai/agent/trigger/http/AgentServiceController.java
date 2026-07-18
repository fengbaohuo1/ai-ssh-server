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
 * V2.1: 步骤事件改为从 ADK Event Stream 提取，移除 AgentStepBuffer + 后台轮询。
 * <p>
 * ADK 管理工具循环后，function_call / function_response 自然出现在 event stream 中。
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
                AiAgentConfigResponseDTO dto = new AiAgentConfigResponseDTO();
                dto.setAgentId(agentConfig.getAgentId());
                dto.setAgentName(agentConfig.getAgentName());
                dto.setAgentDesc(agentConfig.getAgentDesc());
                return dto;
            }).collect(Collectors.toList());
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(responseDTOS).build();
        } catch (AppException e) {
            return Response.<List<AiAgentConfigResponseDTO>>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("查询失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO dto) {
        try {
            String sid = chatService.createSession(dto.getAgentId(), dto.getUserId());
            CreateSessionResponseDTO resp = new CreateSessionResponseDTO();
            resp.setSessionId(sid);
            return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(resp).build();
        } catch (Exception e) {
            log.error("创建会话失败", e);
            return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO dto) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", dto.getAgentId(), dto.getUserId());
            String sid = dto.getSessionId();
            if (sid == null || sid.isEmpty()) sid = chatService.createSession(dto.getAgentId(), dto.getUserId());

            List<String> outputs = chatService.handleMessage(dto.getAgentId(), dto.getUserId(), sid, dto.getMessage());
            StringBuilder text = new StringBuilder();
            List<String> steps = new ArrayList<>();
            for (String o : outputs) {
                if (o.startsWith("Function Call:") || o.startsWith("Function Response:")) {
                    steps.add(parseStep(o));
                } else if (!o.isEmpty()) {
                    text.append(o);
                }
            }
            ChatResponseDTO resp = new ChatResponseDTO();
            resp.setContent(text.toString());
            if (!steps.isEmpty()) resp.setSteps("[" + String.join(",", steps) + "]");
            return Response.<ChatResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(resp).build();
        } catch (Exception e) {
            log.error("对话失败", e);
            return Response.<ChatResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO dto) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        try { emitter.send(SseEmitter.event().data("{\"v\":\"\",\"s\":{\"t\":\"thinking\"}}")); } catch (Exception ignored) {}
        try {
            chatService.handleMessageStream(
                            dto.getAgentId(), dto.getUserId(), dto.getSessionId(), dto.getMessage(),
                            dto.getTerminalSessionId())
                    .subscribe(
                            event -> sendSse(emitter, event),
                            error -> emitter.completeWithError(error),
                            () -> emitter.complete());
        } catch (Exception e) { emitter.completeWithError(e); }
        return emitter;
    }

    private void sendSse(SseEmitter emitter, Event event) {
        try {
            String step = buildStep(event);
            String text = eventText(event);
            // 保留所有内容包括 \n\n（Markdown 段落分隔依赖它）
            if (step != null && text != null && !text.isEmpty())
                emitter.send(SseEmitter.event().data("{\"v\":" + toJson(text) + ",\"s\":[" + step + "]}"));
            else if (step != null)
                emitter.send(SseEmitter.event().data("{\"v\":\"\",\"s\":" + step + "}"));
            else if (text != null && !text.isEmpty())
                emitter.send(SseEmitter.event().data("{\"v\":" + toJson(text) + "}"));
        } catch (Exception ignored) {}
    }

    private String buildStep(Event e) {
        var fcs = e.functionCalls(); var frs = e.functionResponses();
        if (fcs.isEmpty() && frs.isEmpty()) return null;
        if (!fcs.isEmpty()) {
            FunctionCall fc = fcs.get(0);
            String args = fc.args().map(a -> {
                try { return MAPPER.writeValueAsString(a); } catch (Exception ex) { return a.toString(); }
            }).orElse("");
            return "{\"t\":\"tool_call\",\"n\":\"" + esc(fc.name().orElse("")) + "\",\"a\":" + esc(trunc(args, 500)) + "}";
        }
        FunctionResponse fr = frs.get(0);
        String resp = fr.response().map(r -> {
            try { return MAPPER.writeValueAsString(r); } catch (Exception ex) { return r.toString(); }
        }).orElse("");
        return "{\"t\":\"tool_result\",\"n\":\"" + esc(fr.name().orElse("")) + "\",\"r\":" + esc(trunc(resp, 1000)) + "}";
    }

    private String eventText(Event e) {
        return e.content().flatMap(c -> c.parts()).map(ps -> ps.stream().filter(p -> p.text().isPresent()).map(p -> p.text().get()).reduce("", String::concat)).orElse("");
    }

    private String parseStep(String s) {
        if (s.startsWith("Function Call:")) {
            String inner = s.substring("Function Call:".length()).trim();
            return "{\"t\":\"tool_call\",\"n\":\"" + esc(field(inner, "name=")) + "\",\"a\":\"" + esc(trunc(field(inner, "args="), 200)) + "\"}";
        }
        if (s.startsWith("Function Response:")) {
            String inner = s.substring("Function Response:".length()).trim();
            return "{\"t\":\"tool_result\",\"n\":\"" + esc(field(inner, "name=")) + "\",\"r\":\"" + esc(trunc(field(inner, "response="), 500)) + "\"}";
        }
        return "{\"t\":\"thinking\"}";
    }

    private String field(String s, String prefix) {
        int i = s.indexOf(prefix); if (i < 0) return "";
        i += prefix.length(); int e = s.indexOf(',', i); if (e < 0) e = s.indexOf('}', i); if (e < 0) e = s.length();
        String v = s.substring(i, e).trim(); while (v.endsWith("}")) v = v.substring(0, v.length() - 1).trim();
        if (v.startsWith("Optional[")) v = v.substring("Optional[".length(), v.length() - 1);
        return v;
    }

    private static String esc(String s) { if (s == null) return ""; return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "").replace("\t", " "); }
    private static String trunc(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0, n) + "..."; }
    private static String toJson(String s) { try { return MAPPER.writeValueAsString(s); } catch (Exception e) { return "\"\""; } }
}

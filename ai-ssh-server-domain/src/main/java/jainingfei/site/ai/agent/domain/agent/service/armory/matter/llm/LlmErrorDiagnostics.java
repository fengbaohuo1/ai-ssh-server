package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用失败诊断——打印服务端响应体 + 本次请求的消息配对形状。
 *
 * <p>为什么单独成类：provider 的 400 大多不是"网络问题"，而是<b>消息序列不合法</b>
 * （tool 消息与前置 tool_calls 不配对、思考字段缺失被回传校验拒绝等）。
 * 只看到 {@code 400 Bad Request} 无从下手，必须拿到服务端原文与请求角色序列——
 * 这两样与主链路逻辑无关，隔离在这里既不污染适配器，也能整体删除而不影响功能。
 */
@Slf4j
public class LlmErrorDiagnostics {

    /** 请求消息形状摘要里展示的单字段长度 */
    private static final int ABBREVIATE_LENGTH = 40;

    /**
     * 记录一次 LLM 调用失败：异常摘要 → 服务端响应体 → 请求消息形状。
     */
    public void logFailure(Throwable error, Prompt prompt, ReasoningDraftProtocol draftProtocol) {
        try {
            log.error("LLM 请求失败 | {} | {}", error.getClass().getSimpleName(), error.getMessage());
            String body = extractHttpResponseBody(error);
            if (body != null) {
                log.error("LLM 错误响应体 | {}", body);
            }
            log.error("LLM 请求形状 | {}", describePrompt(prompt, draftProtocol));
        } catch (Exception e) {
            log.debug("请求诊断信息输出失败: {}", e.getMessage());
        }
    }

    /**
     * 尽力取出 HTTP 错误响应体（provider 的 400 详情都在里面）。
     * <p>用反射而不是直接引用 {@code WebClientResponseException}：那个类型来自 webflux，
     * 不在本模块的依赖集里；而"错误体"本身是协议层的通用能力，不该为了打印日志
     * 给领域模块引入一个 web 客户端依赖。
     */
    private String extractHttpResponseBody(Throwable error) {
        try {
            java.lang.reflect.Method method = error.getClass().getMethod("getResponseBodyAsString");
            Object body = method.invoke(error);
            return body == null ? null : String.valueOf(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 请求消息形状摘要：角色序列 + tool_calls/tool 响应 id 配对 + 是否带草稿（仅供排查） */
    private String describePrompt(Prompt prompt, ReasoningDraftProtocol draftProtocol) {
        List<String> parts = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String role = String.valueOf(message.getMessageType());
            if (message instanceof AssistantMessage assistant) {
                List<String> callIds = assistant.getToolCalls().stream()
                        .map(tc -> tc.id() + "/" + tc.name())
                        .toList();
                boolean hasReasoning = draftProtocol != null && draftProtocol.hasDraft(assistant);
                parts.add("assistant(toolCalls=" + callIds + ",reasoning=" + hasReasoning
                        + ",text=" + abbreviate(assistant.getText()) + ")");
            } else if (message instanceof ToolResponseMessage toolMessage) {
                List<String> responseIds = toolMessage.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::id)
                        .toList();
                parts.add("tool(ids=" + responseIds + ")");
            } else {
                parts.add(role + "(text=" + abbreviate(message.getText()) + ")");
            }
        }
        return String.join(" -> ", parts);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= ABBREVIATE_LENGTH
                ? text.replace('\n', '⏎')
                : text.substring(0, ABBREVIATE_LENGTH).replace('\n', '⏎') + "…";
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.adk;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import com.google.adk.models.springai.ToolConverter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 修复版 SpringAI适配器：修正 ADK MessageConverter 不转换 functionResponse 的问题。
 * <p>
 * {@code handleUserContent} 中原有 TODO 跳过 functionResponse → ToolResponseMessage 转换，
 * 导致工具执行结果无法回填对话，DeepSeek API 返回 400。
 */
public class FixedSpringAI extends BaseLlm {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    public FixedSpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = chatModel;
        this.streamingChatModel = chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest req, boolean stream) {
        try {
            Prompt prompt = toPrompt(req);
            if (stream && streamingChatModel != null) {
                return Flowable.fromPublisher(
                        streamingChatModel.stream(prompt)
                                .filter(this::isNotEmpty)
                                .map(this::toLlmResponse));
            }
            ChatResponse resp = chatModel.call(prompt);
            return Flowable.just(toLlmResponse(resp));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest req) {
        throw new UnsupportedOperationException("Live connection not supported");
    }

    // ===== LlmRequest → Prompt =====

    private Prompt toPrompt(LlmRequest req) {
        List<Message> messages = new ArrayList<>();
        List<String> systemTexts = new ArrayList<>(req.getSystemInstructions());

        for (Content content : req.contents()) {
            String role = content.role().orElse("user").toLowerCase();
            switch (role) {
                case "system":
                    content.parts().ifPresent(parts -> {
                        StringBuilder sb = new StringBuilder();
                        for (Part p : parts) p.text().ifPresent(sb::append);
                        if (sb.length() > 0) systemTexts.add(sb.toString());
                    });
                    break;
                case "user":
                    messages.addAll(toUserMessages(content));
                    break;
                case "model":
                case "assistant":
                    messages.add(toAssistantMessage(content));
                    break;
                default:
                    messages.addAll(toUserMessages(content));
            }
        }

        if (!systemTexts.isEmpty()) {
            messages.add(0, new SystemMessage(String.join("\n\n", systemTexts)));
        }

        // 工具声明：LlmRequest.tools() → ToolCallback → ToolCallingChatOptions
        ToolCallingChatOptions opts = null;
        if (req.tools() != null && !req.tools().isEmpty()) {
            ToolConverter converter = new ToolConverter();
            List<ToolCallback> callbacks = converter.convertToSpringAiTools(req.tools());
            if (!callbacks.isEmpty()) {
                opts = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
            }
        }

        return new Prompt(messages, opts);
    }

    // ===== Content → Message 转换 =====

    private List<Message> toUserMessages(Content content) {
        List<Message> list = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                text.append(part.text().get());
            } else if (part.functionResponse().isPresent()) {
                // ★ 修复：正确转换 functionResponse → ToolResponseMessage
                FunctionResponse fr = part.functionResponse().get();
                String id = fr.id().orElse("");
                String name = fr.name().orElse("");
                String data = fr.response().map(Object::toString).orElse("{}");
                toolResponses.add(new ToolResponseMessage.ToolResponse(id, name, data));
            }
        }

        String txt = text.toString();
        if (!txt.isEmpty() || toolResponses.isEmpty()) {
            list.add(new UserMessage(txt));
        }
        if (!toolResponses.isEmpty()) {
            list.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        return list;
    }

    private AssistantMessage toAssistantMessage(Content content) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                text.append(part.text().get());
            } else if (part.functionCall().isPresent()) {
                FunctionCall fc = part.functionCall().get();
                toolCalls.add(new AssistantMessage.ToolCall(
                        fc.id().orElse(""),
                        "function",
                        fc.name().orElse(""),
                        fc.args().map(args -> {
                            try { return MAPPER.writeValueAsString(args); } catch (Exception e) { return "{}"; }
                        }).orElse("{}")));
            }
        }

        if (toolCalls.isEmpty()) {
            return new AssistantMessage(text.toString());
        }
        return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
    }

    // ===== ChatResponse → LlmResponse =====

    /**
     * 过滤 Spring AI 流式的完全空 chunk（没有任何内容也没有工具调用）。
     * 不过滤纯空白文本（如 \n\n）——流式模式下无法用 token 数可靠区分 Spring AI meta-event 和 LLM 段落分隔，
     * 换行规整交给前端 norm() 函数。
     */
    private boolean isNotEmpty(ChatResponse cr) {
        if (cr == null || cr.getResults().isEmpty()) return false;
        AssistantMessage am = cr.getResult().getOutput();
        boolean hasText = am.getText() != null && !am.getText().isEmpty();
        boolean hasTools = !am.getToolCalls().isEmpty();
        return hasText || hasTools;
    }

    private LlmResponse toLlmResponse(ChatResponse cr) {
        if (cr == null || cr.getResults().isEmpty()) {
            return LlmResponse.builder().build();
        }
        AssistantMessage am = cr.getResult().getOutput();
        String rawText = am.getText();

        List<Part> parts = new ArrayList<>();
        if (rawText != null && !rawText.isEmpty()) {
            parts.add(Part.fromText(rawText));
        }
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            if ("function".equals(tc.type())) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = MAPPER.readValue(tc.arguments(), Map.class);
                    parts.add(Part.builder().functionCall(
                            FunctionCall.builder().id(tc.id()).name(tc.name()).args(args).build()).build());
                } catch (Exception ignored) {}
            }
        }
        return LlmResponse.builder()
                .content(Content.builder().role("model").parts(parts).build())
                .build();
    }

    private static String extractModelName(Object model) {
        return model.getClass().getSimpleName().toLowerCase()
                .replace("chatmodel", "").replace("model", "");
    }
}

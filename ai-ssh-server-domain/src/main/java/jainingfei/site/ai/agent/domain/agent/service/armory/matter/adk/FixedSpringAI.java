package jainingfei.site.ai.agent.domain.agent.service.armory.matter.adk;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.ToolConverter;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADK Spring AI 适配器——Spring AI 做 LLM 通信，ADK 做工具编排。
 *
 * <pre>
 * 工具流：
 *   toPrompt(): ADK 工具 → ToolConverter → Spring AI ToolCallback → LLM（原生 function calling）
 *   toLlmResponse(): Spring AI ChatResponse → ADK LlmResponse（文本 + FunctionCall parts）
 *
 * \n\n 处理：
 *   isNotEmpty() 上下文感知——流中已出现 tool call 后过滤纯空白 chunk（artifact），
 *   纯文本对话中保留（真实段落分隔）。
 * </pre>
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
                boolean hasTools = req.tools() != null && !req.tools().isEmpty();
                AtomicInteger consecutiveWs = new AtomicInteger(0);

                return Flowable.fromPublisher(
                        streamingChatModel.stream(prompt)
                                .filter(cr -> isNotEmpty(cr, hasTools, consecutiveWs))
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

        // 工具声明：ADK 工具 → Spring AI ToolCallback → ToolCallingChatOptions
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
                FunctionResponse fr = part.functionResponse().get();
                String id = fr.id().orElse("");
                String name = fr.name().orElse("");
                String data = fr.response().map(r -> {
                    try { return MAPPER.writeValueAsString(r); } catch (Exception e) { return "{}"; }
                }).orElse("{}");
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
     * 上下文感知的流式 chunk 过滤。
     *
     * <ul>
     *   <li>无工具 → 纯文本 → 只过滤 null/空，保留段落分隔</li>
     *   <li>有工具 → 前 2 个纯空白 chunk 可能是真实段落分隔，保留；
     *       连续第 3 个起是 Spring AI artifact，过滤</li>
     *   <li>出现非空白 chunk 时，连续计数器重置</li>
     * </ul>
     */
    private static boolean isNotEmpty(ChatResponse cr, boolean hasTools, AtomicInteger consecutiveWs) {
        if (cr == null || cr.getResults().isEmpty()) return false;
        AssistantMessage am = cr.getResult().getOutput();

        // tool call chunk 始终保留，重置计数器
        if (!am.getToolCalls().isEmpty()) {
            consecutiveWs.set(0);
            return true;
        }

        String text = am.getText();
        if (text == null || text.isEmpty()) return false;

        if (hasTools && text.trim().isEmpty()) {
            int count = consecutiveWs.incrementAndGet();
            // 前 2 个纯空白可能是真实段落分隔（如 \n\n## 标题），保留
            // 第 3 个起是 Spring AI tool-mode artifact，过滤
            return count <= 2;
        }

        // 非空白内容 → 重置计数器
        consecutiveWs.set(0);
        return true;
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

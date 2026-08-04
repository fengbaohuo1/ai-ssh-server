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
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADK Spring AI 适配器——Spring AI 做 LLM 通信，ADK 做工具编排。
 *
 * <pre>
 * 工具流：
 *   toPrompt(): ADK 工具 → ToolConverter → Spring AI ToolCallback → LLM（原生 function calling）
 *   toLlmResponse(): Spring AI ChatResponse → ADK LlmResponse（文本 + FunctionCall parts）
 *
 * 单轮模式（singleTurnMode=true）：
 *   工具调用不转成 ADK FunctionCall part，改为 &lt;tool_calls&gt; 文本标签——
 *   ADK 看不到 function call，每次 runAsync() 只跑一轮 LLM。
 *   工具执行与循环推进由 Case 层（显式 ReAct 编排）接管。
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

    /** 单轮模式：工具调用以 &lt;tool_calls&gt; 文本标签输出，不触发 ADK 内部工具循环 */
    private final boolean singleTurnMode;

    public FixedSpringAI(ChatModel chatModel) {
        this(chatModel, false);
    }

    public FixedSpringAI(ChatModel chatModel, boolean singleTurnMode) {
        super(extractModelName(chatModel));
        this.chatModel = chatModel;
        this.streamingChatModel = chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null;
        this.singleTurnMode = singleTurnMode;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest req, boolean stream) {
        try {
            Prompt prompt = toPrompt(req);
            if (stream && streamingChatModel != null) {
                boolean hasTools = req.tools() != null && !req.tools().isEmpty();

                if (hasTools) {
                    // 工具模式：空白 chunk 缓冲合并。
                    // 真实换行（Markdown 表格/段落的分隔）是独立空白 chunk——缓冲后随下一文本 chunk
                    // 一起发出，完整保留；工具调用 chunk 到达时丢弃缓冲（phantom 空白过滤）。
                    AtomicReference<String> pendingWs = new AtomicReference<>("");
                    return Flowable.fromPublisher(streamingChatModel.stream(prompt))
                            .flatMap(cr -> mergeWhitespace(cr, pendingWs))
                            .map(this::toLlmResponse);
                }

                // 无工具模式：只过滤 null/空，保留段落分隔
                return Flowable.fromPublisher(
                        streamingChatModel.stream(prompt)
                                .filter(FixedSpringAI::isNotEmpty)
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
     * 工具模式空白缓冲合并——替代旧的"连续空白计数过滤"。
     *
     * <p>旧策略保留前 2 个空白 chunk，误杀真实换行（Markdown 表格/多行文本的换行
     * 也是独立空白 chunk，数量远超 2）；本策略改为：
     * <ul>
     *   <li>空白 chunk → 缓冲（不发），等下一个文本 chunk</li>
     *   <li>文本 chunk → 缓冲的空白 + 文本合并发出（真实换行完整保留）</li>
     *   <li>工具调用 chunk / 流结束 → 丢弃缓冲（phantom 空白过滤）</li>
     * </ul>
     * 真实换行保留与 phantom 过滤兼得，不依赖 phantom 的具体位置。
     */
    private static Flowable<ChatResponse> mergeWhitespace(ChatResponse cr, AtomicReference<String> pendingWs) {
        if (cr == null || cr.getResults().isEmpty()) return Flowable.empty();
        AssistantMessage am = cr.getResult().getOutput();

        // 工具调用 chunk：丢弃缓冲的空白（phantom），直接发出
        if (!am.getToolCalls().isEmpty()) {
            pendingWs.set("");
            return Flowable.just(cr);
        }

        String text = am.getText();
        if (text == null || text.isEmpty()) return Flowable.empty();

        // 纯空白 chunk：缓冲，等待下一个文本 chunk 合并
        if (text.trim().isEmpty()) {
            pendingWs.set(pendingWs.get() + text);
            return Flowable.empty();
        }

        // 文本 chunk：合并缓冲的空白后发出
        String combined = pendingWs.get() + text;
        pendingWs.set("");
        return Flowable.just(withText(cr, combined));
    }

    /** 构造带合并文本的 ChatResponse（文本 chunk 无 toolCalls，安全复制） */
    private static ChatResponse withText(ChatResponse cr, String text) {
        AssistantMessage am = AssistantMessage.builder().content(text).build();
        return ChatResponse.builder()
                .generations(List.of(new org.springframework.ai.chat.model.Generation(am)))
                .metadata(cr.getMetadata())
                .build();
    }

    /** 无工具模式过滤：只过滤 null/空，保留段落分隔 */
    private static boolean isNotEmpty(ChatResponse cr) {
        if (cr == null || cr.getResults().isEmpty()) return false;
        AssistantMessage am = cr.getResult().getOutput();
        String text = am.getText();
        return text != null && !text.isEmpty();
    }

    private LlmResponse toLlmResponse(ChatResponse cr) {
        if (cr == null || cr.getResults().isEmpty()) {
            return LlmResponse.builder().build();
        }
        AssistantMessage am = cr.getResult().getOutput();
        String rawText = am.getText();

        // 单轮模式：工具调用转 <tool_calls> 文本标签（每行一个 JSON），不生成 FunctionCall part。
        // ADK 看不到 function call → 本次 runAsync() 一轮即止，工具执行由 Case 层显式接管。
        if (singleTurnMode && !am.getToolCalls().isEmpty()) {
            StringBuilder text = new StringBuilder(rawText == null ? "" : rawText);
            text.append("\n<tool_calls>\n");
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if ("function".equals(tc.type())) {
                    // tc.arguments() 已是合法 JSON 字符串（Spring AI 解析产物），直接嵌入
                    text.append("{\"name\":\"").append(tc.name())
                            .append("\",\"args\":").append(tc.arguments()).append("}\n");
                }
            }
            text.append("</tool_calls>\n");
            return LlmResponse.builder()
                    .content(Content.builder().role("model").parts(List.of(Part.fromText(text.toString()))).build())
                    .build();
        }

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

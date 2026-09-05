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
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.completions.CompletionUsage;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

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
@Slf4j
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
            // 每次模型调用独立的草稿累积器（DeepSeek thinking：草稿分片先于正文到达）
            ReasoningAccumulator reasoningAcc = new ReasoningAccumulator();
            if (stream && streamingChatModel != null) {
                boolean hasTools = req.tools() != null && !req.tools().isEmpty();

                // 流式 usage 只在末块返回，且末块通常无文本——会被下方空白过滤/缓冲逻辑丢弃，
                // 因此在过滤之前观察每个 chunk，记录最后一次出现的 usage，流结束后统一输出
                AtomicReference<Usage> usageRef = new AtomicReference<>();
                // 同时在过滤前捕获 DeepSeek 思考草稿分片（reasoning chunk 文本为空，不进正文流）
                Flowable<ChatResponse> observed = Flowable.fromPublisher(
                                streamingChatModel.stream(prompt))
                        .doOnNext(cr -> observeUsage(cr, usageRef))
                        .doOnNext(cr -> captureReasoning(cr, reasoningAcc));

                if (hasTools) {
                    // 工具模式：空白 chunk 缓冲合并（phantom 空白过滤），真实换行保留
                    AtomicReference<String> pendingWs = new AtomicReference<>("");
                    return observed
                            .flatMap(cr -> mergeWhitespace(cr, pendingWs))
                            .map(cr -> toLlmResponse(cr, reasoningAcc))
                            .doOnComplete(() -> logUsage(usageRef.get()));
                }

                // 无工具模式：只过滤 null/空，保留段落分隔
                return observed
                        .filter(FixedSpringAI::isNotEmpty)
                        .map(cr -> toLlmResponse(cr, reasoningAcc))
                        .doOnComplete(() -> logUsage(usageRef.get()));
            }
            ChatResponse resp = chatModel.call(prompt);
            logUsage(resp.getMetadata() != null ? resp.getMetadata().getUsage() : null);
            // 同步路径一次拿全量响应：先捕获草稿再注入（与流式路径一致）
            captureReasoning(resp, reasoningAcc);
            return Flowable.just(toLlmResponse(resp, reasoningAcc));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    // ===== DeepSeek 思考草稿（reasoning_content）捕获与注入 =====

    /** 单次模型调用的草稿状态：分片累积 + 确保只注入一次 */
    private static final class ReasoningAccumulator {
        final StringBuilder reasoning = new StringBuilder();
        boolean seen = false;
        boolean injected = false;
    }

    /**
     * 从流式 chunk 捕获思考草稿分片（原生 DeepSeek 通道的 DeepSeekAssistantMessage 携带
     * reasoningContent；OpenAI 兼容通道无此类型则自然跳过）。
     */
    private static void captureReasoning(ChatResponse cr, ReasoningAccumulator acc) {
        if (cr == null || cr.getResults().isEmpty()) {
            return;
        }
        if (cr.getResult().getOutput() instanceof DeepSeekAssistantMessage ds) {
            String frag = ds.getReasoningContent();
            if (frag != null && !frag.isEmpty()) {
                acc.seen = true;
                acc.reasoning.append(frag);
            }
        }
    }

    /**
     * 首个"可见内容"chunk 到达时，把已聚合的完整草稿以隐藏标记前缀注入该 chunk 文本
     * （DeepSeek 的思考分片总是先于正文/工具调用分片到达）。标记随 assistant 事件文本落库，
     * 下一轮重建历史时解析并放回 DeepSeekAssistantMessage.reasoningContent（L2 草稿延续）。
     */
    private static String reasoningPrefix(ReasoningAccumulator acc, boolean firstVisibleContent) {
        if (firstVisibleContent && acc.seen && !acc.injected && acc.reasoning.length() > 0) {
            acc.injected = true;
            return ReasoningTagUtil.wrap(acc.reasoning.toString());
        }
        return "";
    }

    // ===== Usage 观测（教程 2-9：缓存命中可验证） =====

    /** 流式路径：在空白过滤前观察 chunk，保留最后一次出现的 usage（末块含完整统计） */
    private static void observeUsage(ChatResponse cr, AtomicReference<Usage> usageRef) {
        if (cr == null || cr.getMetadata() == null) return;
        Usage usage = cr.getMetadata().getUsage();
        if (usage != null) {
            usageRef.set(usage);
        }
    }

    /** 输出一次模型调用的 token 消耗（含缓存命中数），用于验证 Prompt Cache 收益 */
    private static void logUsage(Usage usage) {
        if (usage == null) return;
        log.info("LLM usage | promptTokens: {} | completionTokens: {} | totalTokens: {} | cachedTokens: {}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                extractCachedTokens(usage));
    }

    /**
     * 提取缓存命中的 token 数：优先 spring-ai 的 CacheRead 字段（2.0.0 起提供），
     * 未填充时回退到 OpenAI SDK 原生 usage 的 prompt_tokens_details.cached_tokens。
     */
    private static Long extractCachedTokens(Usage usage) {
        try {
            Long cached = usage.getCacheReadInputTokens();
            if (cached != null) {
                return cached;
            }
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof CompletionUsage completionUsage) {
                return completionUsage.promptTokensDetails()
                        .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                        .orElse(null);
            }
            if (nativeUsage instanceof ChatCompletion chatCompletion) {
                return chatCompletion.usage()
                        .flatMap(u -> u.promptTokensDetails())
                        .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                        .orElse(null);
            }
            // DeepSeek 原生通道（spring-ai-deepseek 2.0.1）
            if (nativeUsage instanceof DeepSeekApi.Usage dsUsage) {
                DeepSeekApi.Usage.PromptTokensDetails details = dsUsage.promptTokensDetails();
                return details != null ? details.cachedTokens().longValue() : null;
            }
        } catch (Exception e) {
            log.debug("提取缓存命中 token 失败: {}", e.getMessage());
        }
        return null;
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
                    // DeepSeek thinking：历史 assistant 必须携带 reasoning_content 回传。
                    // 事件文本含隐藏草稿标记 → 解析后构造 DeepSeekAssistantMessage；
                    // 无草稿（旧会话/标记损坏）→ 跳过该条历史（宁可丢上轮回复文本，
                    // 工具结果/上下文都在 user 消息里，也不触发服务端 400）
                    messages.addAll(toHistoryAssistantMessages(content));
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

    /**
     * 历史 assistant 事件 → spring-ai 消息列表（0 或 1 条）：
     * 解析事件文本中的隐藏草稿标记，构造 {@link DeepSeekAssistantMessage}（草稿放
     * reasoningContent、正文放 content）；无草稿时返回空列表（跳过该条历史）。
     */
    private List<Message> toHistoryAssistantMessages(Content content) {
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

        String raw = text.toString();
        String[] parsed = ReasoningTagUtil.parse(raw);
        String reasoning = parsed[0];
        String visible = parsed[1] == null ? "" : parsed[1];
        if (reasoning == null) {
            // 无草稿：整条跳过（DeepSeek thinking+工具场景的历史 assistant 必须带草稿回传）
            return List.of();
        }
        DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder()
                .content(visible)
                .reasoningContent(reasoning);
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return List.of(builder.build());
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

    private LlmResponse toLlmResponse(ChatResponse cr, ReasoningAccumulator acc) {
        if (cr == null || cr.getResults().isEmpty()) {
            return LlmResponse.builder().build();
        }
        AssistantMessage am = cr.getResult().getOutput();
        String rawText = am.getText();
        boolean firstVisibleContent = (rawText != null && !rawText.isEmpty()) || !am.getToolCalls().isEmpty();
        String textPrefix = reasoningPrefix(acc, firstVisibleContent);
        String text = textPrefix + (rawText == null ? "" : rawText);

        // 单轮模式：工具调用转 <tool_calls> 文本标签（每行一个 JSON），不生成 FunctionCall part。
        // ADK 看不到 function call → 本次 runAsync() 一轮即止，工具执行由 Case 层显式接管。
        if (singleTurnMode && !am.getToolCalls().isEmpty()) {
            StringBuilder sb = new StringBuilder(text);
            sb.append("\n<tool_calls>\n");
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if ("function".equals(tc.type())) {
                    // tc.arguments() 已是合法 JSON 字符串（Spring AI 解析产物），直接嵌入
                    sb.append("{\"name\":\"").append(tc.name())
                            .append("\",\"args\":").append(tc.arguments()).append("}\n");
                }
            }
            sb.append("</tool_calls>\n");
            return LlmResponse.builder()
                    .content(Content.builder().role("model").parts(List.of(Part.fromText(sb.toString()))).build())
                    .build();
        }

        List<Part> parts = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            parts.add(Part.fromText(text));
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

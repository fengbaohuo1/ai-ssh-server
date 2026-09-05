package jainingfei.site.ai.agent.domain.agent.service.armory.matter.adk;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.ToolConverter;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.completions.CompletionUsage;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADK Spring AI 适配器——Spring AI 做 LLM 通信，ADK 做工具循环 + 编排（框架托管循环）。
 *
 * <pre>
 * 工具流（托管循环模式，V3）：
 *   toPrompt(): ADK 工具 → ToolConverter → Spring AI ToolCallback → LLM（原生 function calling）
 *   toLlmResponse(): Spring AI ChatResponse → ADK LlmResponse（文本 + FunctionCall parts）
 *
 * 流式 partial/聚合语义（对齐 ADK Gemini 适配器，V3）：
 *   每次 LLM 调用：
 *     1. 文本分片 → partial=true 的 LlmResponse 即时发出（Runner 只转发不持久化，SSE 实时）
 *     2. 工具调用分片 → 不单独发事件，聚合进本次调用
 *     3. 流结束 → 发一条聚合 LlmResponse（partial 为空）：
 *        content = [隐藏草稿标记前缀 + 可见全文文本] + FunctionCall parts
 *        该事件是唯一被 Runner 持久化的 model 事件——会话历史里一次 LLM 调用一条
 *        assistant 事件（替代旧的"每分片一条事件 + SessionService 碎片合并"）。
 *        草稿标记只随聚合事件落库（分片不持久化，无需带标记），下轮重建历史时
 *        解析并放回 DeepSeekAssistantMessage.reasoningContent（L2 草稿延续）。
 *    usageMetadata 映射到聚合事件——供 ADK 事件压缩（tail-retention）读取真实
 *    promptTokenCount 触发折叠。
 *
 * 空白 chunk 处理（统一策略）：
 *   纯空白 chunk → 缓冲，等下一个文本 chunk 合并发出（phantom 空白过滤，真实换行保留）；
 *   文本 chunk → 缓冲的空白 + 文本合并发出；流结束仍滞留的空白 → 丢弃（phantom）。
 * </pre>
 */
@Slf4j
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
            // 每次模型调用独立的草稿累积器（DeepSeek thinking：草稿分片先于正文到达）
            ReasoningAccumulator reasoningAcc = new ReasoningAccumulator();
            if (stream && streamingChatModel != null) {
                // 流式 usage 只在末块返回，且末块通常无文本——会被下方空白过滤/缓冲逻辑丢弃，
                // 因此在过滤之前观察每个 chunk，记录最后一次出现的 usage，聚合事件上统一输出
                AtomicReference<Usage> usageRef = new AtomicReference<>();
                // 单次调用的流式状态：已发出的文本 + 已解析的工具调用（按 id 去重聚合）
                StreamAccumulator streamAcc = new StreamAccumulator();
                Flowable<ChatResponse> observed = Flowable.fromPublisher(
                                streamingChatModel.stream(prompt))
                        .doOnNext(cr -> observeUsage(cr, usageRef))
                        .doOnNext(cr -> captureReasoning(cr, reasoningAcc));

                // ① 分片事件（partial=true，Runner 不持久化，仅流式转发）
                Flowable<LlmResponse> partials = observed
                        .flatMap(cr -> toPartialLlmResponse(cr, streamAcc));
                // ② 聚合事件（partial=null，唯一落库的 model 事件）
                return partials.concatWith(
                        Flowable.defer(() -> buildAggregated(reasoningAcc, streamAcc, usageRef)));
            }
            // 同步路径一次拿全量响应：先捕获草稿再注入（与流式路径一致）
            ChatResponse resp = chatModel.call(prompt);
            captureReasoning(resp, reasoningAcc);
            StreamAccumulator streamAcc = new StreamAccumulator();
            List<AssistantMessage.ToolCall> toolCalls = resp.getResult().getOutput().getToolCalls();
            if (toolCalls != null) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    if ("function".equals(tc.type())) {
                        streamAcc.mergeToolCall(tc);
                    }
                }
            }
            if (resp.getResult().getOutput().getText() != null) {
                streamAcc.visible.append(resp.getResult().getOutput().getText());
            }
            return buildAggregated(reasoningAcc, streamAcc, new AtomicReference<>(
                    resp.getMetadata() != null ? resp.getMetadata().getUsage() : null));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    // ===== DeepSeek 思考草稿（reasoning_content）捕获与注入 =====

    /** 单次模型调用的草稿状态：分片累积 + 确保只注入一次 */
    private static final class ReasoningAccumulator {
        final StringBuilder reasoning = new StringBuilder();
        boolean seen = false;
    }

    /** 单次流式调用的聚合状态：可见文本累积 + 工具调用聚合（按 id 去重） */
    private static final class StreamAccumulator {
        final StringBuilder visible = new StringBuilder();
        /** 纯空白 chunk 缓冲（phantom 过滤：等下一个文本 chunk 合并后一起发出） */
        final StringBuilder pendingWs = new StringBuilder();
        /** 工具调用 id → ToolCall（DeepSeek 流式分片可能重复携带完整 tool_calls，按 id 取最新） */
        final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();

        void mergeToolCall(AssistantMessage.ToolCall tc) {
            String id = tc.id() != null ? tc.id() : "";
            if (toolCalls.containsKey(id) && tc.arguments() == null) {
                return;
            }
            toolCalls.put(id, tc);
        }

        boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        List<AssistantMessage.ToolCall> toolCallList() {
            return new ArrayList<>(toolCalls.values());
        }
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

    /** 提取缓存命中的 token 数：优先 spring-ai 的 CacheRead 字段（2.0.0 起提供），
     * 未填充时回退到 OpenAI SDK 原生 usage 的 prompt_tokens_details.cached_tokens。 */
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

    /** spring-ai Usage → genai usageMetadata（压缩引擎读取真实 promptTokenCount） */
    private static GenerateContentResponseUsageMetadata toUsageMetadata(Usage usage) {
        if (usage == null) {
            return null;
        }
        try {
            GenerateContentResponseUsageMetadata.Builder builder = GenerateContentResponseUsageMetadata.builder();
            if (usage.getPromptTokens() != null) builder.promptTokenCount(usage.getPromptTokens());
            if (usage.getCompletionTokens() != null) builder.candidatesTokenCount(usage.getCompletionTokens());
            if (usage.getTotalTokens() != null) builder.totalTokenCount(usage.getTotalTokens());
            Long cached = extractCachedTokens(usage);
            if (cached != null) builder.cachedContentTokenCount(cached.intValue());
            return builder.build();
        } catch (Exception e) {
            log.debug("usage → usageMetadata 映射失败: {}", e.getMessage());
            return null;
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
     * reasoningContent、正文放 content）。
     *
     * <p>降级策略（V3 实测校准）：DeepSeek 并非每次调用都产出 reasoning_content——
     * 无草稿标记的文本事件跳过（L1 兜底，防"原响应有草稿却未回传"的 400）；
     * 但<b>携带 FunctionCall 的 assistant 事件必须保留</b>（tool 消息要与前置
     * tool_calls 配对，整条跳过会导致 "Messages with role 'tool' must be a response
     * to a preceding message with 'tool_calls'" 400），无标记时构造不带草稿的消息即可。
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

        DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder();
        if (reasoning != null) {
            builder.reasoningContent(reasoning);
        } else if (toolCalls.isEmpty()) {
            // 无草稿且无工具调用：整条跳过（DeepSeek thinking+工具场景无法确认原文是否带草稿）
            return List.of();
        }
        // DeepSeekChatModel.createRequest 对 assistant 消息断言 content 文本非 null——
        // 纯工具调用消息可见文本为空时回退空串（内容由 tool_calls 承载）
        builder.content(visible);
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return List.of(builder.build());
    }

    // ===== ChatResponse → LlmResponse（分片 / 聚合） =====

    /**
     * 分片转换（流式）：文本 chunk → partial=true 事件；工具调用 chunk → 聚合进
     * {@link StreamAccumulator} 不单独发事件；空白 chunk → 缓冲合并策略（phantom 过滤，
     * 真实换行保留），与旧的工具模式行为一致。
     */
    private static Flowable<LlmResponse> toPartialLlmResponse(ChatResponse cr, StreamAccumulator acc) {
        if (cr == null || cr.getResults().isEmpty()) return Flowable.empty();
        AssistantMessage am = cr.getResult().getOutput();

        // 工具调用 chunk：不单独发事件（聚合进本次调用，最终由聚合事件携带）
        if (!am.getToolCalls().isEmpty()) {
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if ("function".equals(tc.type())) {
                    acc.mergeToolCall(tc);
                }
            }
            return Flowable.empty();
        }

        String text = am.getText();
        if (text == null || text.isEmpty()) return Flowable.empty();

        // 纯空白 chunk：缓冲，等待下一个文本 chunk 合并（phantom 空白过滤）
        if (text.trim().isEmpty()) {
            acc.pendingWs.append(text);
            return Flowable.empty();
        }

        // 文本 chunk：合并缓冲的空白后发出（真实换行完整保留）
        String combined = acc.pendingWs + text;
        acc.pendingWs.setLength(0);
        acc.visible.append(combined);
        return Flowable.just(LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText(combined))).build())
                .partial(true)
                .build());
    }

    /**
     * 聚合事件（一次 LLM 调用一条，partial=null → Runner 持久化）：
     * 文本部分 = 隐藏草稿标记前缀（如有草稿）+ 已发出分片文本；随后是全部 FunctionCall parts；
     * usageMetadata 随事件落库（压缩引擎据此触发）。
     */
    private Flowable<LlmResponse> buildAggregated(ReasoningAccumulator reasoningAcc,
                                                  StreamAccumulator acc,
                                                  AtomicReference<Usage> usageRef) {
        List<Part> parts = new ArrayList<>();
        boolean hasReasoning = reasoningAcc.seen && reasoningAcc.reasoning.length() > 0;
        String visible = acc.visible.toString();
        if (hasReasoning || !visible.isEmpty()) {
            String prefix = hasReasoning ? ReasoningTagUtil.wrap(reasoningAcc.reasoning.toString()) : "";
            parts.add(Part.fromText(prefix + visible));
        }
        for (AssistantMessage.ToolCall tc : acc.toolCallList()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = MAPPER.readValue(tc.arguments(), Map.class);
                parts.add(Part.builder().functionCall(
                        FunctionCall.builder().id(tc.id()).name(tc.name()).args(args).build()).build());
            } catch (Exception ignored) {
            }
        }
        if (parts.isEmpty()) {
            // 无可见文本、无草稿、无工具调用（如仅 usage 的空流）——不产出事件
            return Flowable.empty();
        }
        LlmResponse.Builder builder = LlmResponse.builder()
                .content(Content.builder().role("model").parts(parts).build());
        if (usageRef != null && usageRef.get() != null) {
            GenerateContentResponseUsageMetadata meta = toUsageMetadata(usageRef.get());
            if (meta != null) {
                builder.usageMetadata(meta);
            }
        }
        logUsage(usageRef != null ? usageRef.get() : null);
        return Flowable.just(builder.build());
    }

    private static String extractModelName(Object model) {
        return model.getClass().getSimpleName().toLowerCase()
                .replace("chatmodel", "").replace("model", "");
    }
}

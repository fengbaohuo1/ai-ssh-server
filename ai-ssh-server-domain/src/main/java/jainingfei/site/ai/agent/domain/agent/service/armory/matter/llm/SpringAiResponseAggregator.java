package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 响应侧转换——Spring AI 的 {@link ChatResponse} → ADK 的 {@link LlmResponse}。
 *
 * <p><b>流式语义（对齐 ADK Gemini 适配器）</b>：一次 LLM 调用产出两类事件：
 * <pre>
 *   ① 分片事件（partial=true）：文本 chunk 即时发出，Runner 只转发不持久化 → SSE 实时
 *   ② 聚合事件（partial=null）：流结束后一条，content = [草稿标记 + 可见全文] + FunctionCall parts
 *      —— 唯一被持久化的 model 事件（会话历史里一次调用一条），usageMetadata 随它落库
 * </pre>
 *
 * <p><b>空白 chunk 策略</b>：纯空白 chunk 先缓冲，等下一个文本 chunk 合并后一起发出
 * （过滤 phantom 空白，真实换行的位置不动）；流结束仍滞留的空白丢弃。
 *
 * <p><b>工具调用</b>：分片里的 tool_calls 不单独发事件，按 id 聚合进本次调用
 * （provider 可能重复携带完整 tool_calls，按 id 取最新）；同步路径则直接取响应里的 tool_calls。
 */
public class SpringAiResponseAggregator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReasoningDraftProtocol draftProtocol;
    private final UsageReporter usageReporter;
    /** 通道的草稿读法能力（null = 走元数据键默认约定） */
    private final ReasoningDraftAccess draftAccess;
    /** 通道的缓存命中读法能力（null = 只读通用字段） */
    private final CacheMetricsAccess cacheMetricsAccess;

    public SpringAiResponseAggregator(ReasoningDraftProtocol draftProtocol,
                                      UsageReporter usageReporter,
                                      ReasoningDraftAccess draftAccess,
                                      CacheMetricsAccess cacheMetricsAccess) {
        this.draftProtocol = draftProtocol;
        this.usageReporter = usageReporter;
        this.draftAccess = draftAccess;
        this.cacheMetricsAccess = cacheMetricsAccess;
    }

    /**
     * 流式：分片流 → LlmResponse 流（分片 + 末尾一条聚合）。
     *
     * @param chunks 已带诊断钩子的模型分片流（顺序在此之后不会被重排，空白过滤策略不变）
     */
    public Flowable<LlmResponse> toLlmResponses(Flowable<ChatResponse> chunks) {
        ReasoningAccumulator draftAcc = new ReasoningAccumulator();
        StreamAccumulator streamAcc = new StreamAccumulator();
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        Flowable<ChatResponse> observed = chunks
                .doOnNext(response -> usageReporter.observe(response, usageRef))
                .doOnNext(response -> captureDraft(response, draftAcc));

        return observed.flatMap(response -> toPartialResponse(response, streamAcc))
                .concatWith(Flowable.defer(() -> {
                    LlmResponse aggregated = aggregate(draftAcc, streamAcc, usageRef.get());
                    return aggregated == null ? Flowable.empty() : Flowable.just(aggregated);
                }));
    }

    /**
     * 同步：一次完整响应 → 一条聚合 LlmResponse。
     *
     * @return 无文本、无草稿、无工具调用时返回 {@code null}（调用方转成空流）
     */
    public LlmResponse toAggregatedResponse(ChatResponse response) {
        ReasoningAccumulator draftAcc = new ReasoningAccumulator();
        captureDraft(response, draftAcc);

        StreamAccumulator streamAcc = new StreamAccumulator();
        AssistantMessage output = response.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if (toolCalls != null) {
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                if ("function".equals(toolCall.type())) {
                    streamAcc.mergeToolCall(toolCall);
                }
            }
        }
        if (output.getText() != null) {
            streamAcc.visible.append(output.getText());
        }
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        return aggregate(draftAcc, streamAcc, usage);
    }

    /** 抓取推理内容分片（机会式：本通道不提供时自然为空） */
    private void captureDraft(ChatResponse response, ReasoningAccumulator accumulator) {
        String fragment = draftProtocol.extractDraftFragment(response, draftAccess);
        if (fragment != null && !fragment.isEmpty()) {
            accumulator.seen = true;
            accumulator.reasoning.append(fragment);
        }
    }

    /**
     * 分片转换：文本 chunk → partial=true 事件；工具调用 chunk → 聚合不单独发事件；
     * 空白 chunk → 缓冲合并（策略见类注释）。
     */
    private static Flowable<LlmResponse> toPartialResponse(ChatResponse response, StreamAccumulator acc) {
        if (response == null || response.getResults().isEmpty()) {
            return Flowable.empty();
        }
        AssistantMessage output = response.getResult().getOutput();

        if (!output.getToolCalls().isEmpty()) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                if ("function".equals(toolCall.type())) {
                    acc.mergeToolCall(toolCall);
                }
            }
            return Flowable.empty();
        }

        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return Flowable.empty();
        }
        if (text.trim().isEmpty()) {
            acc.pendingWhitespace.append(text);
            return Flowable.empty();
        }

        String combined = acc.pendingWhitespace + text;
        acc.pendingWhitespace.setLength(0);
        acc.visible.append(combined);
        return Flowable.just(LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText(combined))).build())
                .partial(true)
                .build());
    }

    /**
     * 聚合事件：文本部分 = 草稿标记前缀（如有）+ 已发出分片文本；随后是全部 FunctionCall parts；
     * usageMetadata 随事件落库（压缩引擎据此触发真实 promptTokenCount）。
     *
     * @return 无可发内容时返回 {@code null}
     */
    private LlmResponse aggregate(ReasoningAccumulator draftAcc, StreamAccumulator acc, Usage usage) {
        List<Part> parts = new ArrayList<>();
        boolean hasDraft = draftAcc.seen && draftAcc.reasoning.length() > 0;
        String visible = acc.visible.toString();
        if (hasDraft || !visible.isEmpty()) {
            String prefix = hasDraft ? draftProtocol.wrapForStorage(draftAcc.reasoning.toString()) : "";
            parts.add(Part.fromText(prefix + visible));
        }
        for (AssistantMessage.ToolCall toolCall : acc.toolCallList()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = MAPPER.readValue(toolCall.arguments(), Map.class);
                parts.add(Part.builder().functionCall(
                        FunctionCall.builder().id(toolCall.id()).name(toolCall.name()).args(args).build()).build());
            } catch (Exception ignored) {
                // 参数不是合法 JSON：跳过该工具调用（与旧行为一致）
            }
        }
        if (parts.isEmpty()) {
            return null;
        }

        LlmResponse.Builder builder = LlmResponse.builder()
                .content(Content.builder().role("model").parts(parts).build());
        if (usage != null) {
            GenerateContentResponseUsageMetadata metadata = usageReporter.toMetadata(usage, cacheMetricsAccess);
            if (metadata != null) {
                builder.usageMetadata(metadata);
            }
        }
        usageReporter.log(usage, cacheMetricsAccess);
        return builder.build();
    }

    // ===== 单次调用状态 =====

    /** 草稿状态：分片累积 + 是否出现过 */
    private static final class ReasoningAccumulator {
        final StringBuilder reasoning = new StringBuilder();
        boolean seen = false;
    }

    /** 聚合状态：可见文本 + 待合并空白 + 工具调用（按 id 去重） */
    private static final class StreamAccumulator {
        final StringBuilder visible = new StringBuilder();
        final StringBuilder pendingWhitespace = new StringBuilder();
        final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();

        void mergeToolCall(AssistantMessage.ToolCall toolCall) {
            String id = toolCall.id() != null ? toolCall.id() : "";
            if (toolCalls.containsKey(id) && toolCall.arguments() == null) {
                return;
            }
            toolCalls.put(id, toolCall);
        }

        List<AssistantMessage.ToolCall> toolCallList() {
            return new ArrayList<>(toolCalls.values());
        }
    }
}

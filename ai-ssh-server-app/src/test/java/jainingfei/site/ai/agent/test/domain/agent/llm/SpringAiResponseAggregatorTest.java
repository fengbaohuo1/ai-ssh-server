package jainingfei.site.ai.agent.test.domain.agent.llm;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.CacheMetricsAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftProtocol;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiResponseAggregator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.UsageReporter;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应聚合器（SpringAiResponseAggregator）单元测试——流式分片/聚合语义，纯内存无网络。
 *
 * <p>对齐 ADK Gemini 适配器的流式约定：
 * <pre>
 *   ① 文本 chunk → partial=true 事件（Runner 只转发不落库，供 SSE 实时显示）
 *   ② 流结束 → 恰好一条 partial=null 的聚合事件（唯一落库的 model 事件）
 *   ③ 工具调用 chunk 不单独发事件，按 id 合并进聚合事件
 *   ④ 纯空白 chunk 先缓冲，等下一个文本 chunk 合并后一起发出；流尾滞留空白丢弃
 * </pre>
 */
public class SpringAiResponseAggregatorTest {

    private final ReasoningDraftProtocol protocol = new ReasoningDraftProtocol();
    private final UsageReporter usageReporter = new UsageReporter();

    private SpringAiResponseAggregator aggregator(ReasoningDraftAccess draftAccess, CacheMetricsAccess cacheAccess) {
        return new SpringAiResponseAggregator(protocol, usageReporter, draftAccess, cacheAccess);
    }

    // ===== 构造响应的工具方法 =====

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).toolCalls(List.of()).build())));
    }

    private static ChatResponse textChunkWithUsage(String text, Usage usage) {
        return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content(text).toolCalls(List.of()).build())),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    private static ChatResponse toolChunk(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }

    private static List<LlmResponse> collect(SpringAiResponseAggregator aggregator, ChatResponse... chunks) {
        return aggregator.toLlmResponses(Flowable.fromArray(chunks)).toList().blockingGet();
    }

    private static String textOf(LlmResponse response) {
        return response.content().flatMap(Content::parts).orElse(List.of()).stream()
                .map(p -> p.text().orElse(""))
                .reduce("", String::concat);
    }

    private static List<Part> functionCallParts(LlmResponse response) {
        return response.content().flatMap(Content::parts).orElse(List.of()).stream()
                .filter(p -> p.functionCall().isPresent())
                .toList();
    }

    private static List<LlmResponse> partials(List<LlmResponse> responses) {
        return responses.stream().filter(r -> r.partial().orElse(false)).toList();
    }

    private static LlmResponse aggregated(List<LlmResponse> responses) {
        return responses.get(responses.size() - 1);
    }

    // ===== 流式：文本分片 =====

    @Test
    @DisplayName("流式：N 个文本 chunk 产出 N 条 partial 事件 + 恰好 1 条聚合事件")
    public void test_stream_partials_plus_single_aggregate() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                textChunk("你好"), textChunk("，"), textChunk("世界"));

        assertEquals(4, responses.size(), "3 条分片 + 1 条聚合");
        assertEquals(3, partials(responses).size(), "只允许一条聚合事件");
        assertEquals("你好", textOf(responses.get(0)));
        assertEquals("，", textOf(responses.get(1)));
        assertEquals("世界", textOf(responses.get(2)));

        LlmResponse last = aggregated(responses);
        assertTrue(last.partial().isEmpty(), "聚合事件不得带 partial 标记（否则会被当成不落库的分片）");
        assertEquals("你好，世界", textOf(last));
        assertEquals("model", last.content().get().role().orElse(null));
    }

    @Test
    @DisplayName("流式：纯空白 chunk 先缓冲，与后续文本 chunk 合并成一条分片")
    public void test_whitespace_chunk_buffered_and_merged() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                textChunk("Hello"), textChunk("\n\n"), textChunk("World"));

        assertEquals(3, responses.size(), "空白 chunk 不得单独成事件");
        assertEquals("Hello", textOf(responses.get(0)));
        assertEquals("\n\nWorld", textOf(responses.get(1)), "空白应与后一个文本 chunk 合并");
        assertEquals("Hello\n\nWorld", textOf(aggregated(responses)), "聚合正文保持原始换行位置");
    }

    @Test
    @DisplayName("流式：流尾滞留的全空白 chunk 被丢弃（不产生分片，也不进入聚合正文）")
    public void test_trailing_whitespace_dropped() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                textChunk("Hello"), textChunk("   "));

        assertEquals(2, responses.size());
        assertEquals(1, partials(responses).size());
        assertEquals("Hello", textOf(aggregated(responses)), "尾部空白不得进入聚合事件");
    }

    @Test
    @DisplayName("流式：只有空白 chunk 时整条流为空（无分片、无聚合）")
    public void test_only_whitespace_yields_nothing() {
        assertTrue(collect(aggregator(null, null), textChunk("   "), textChunk("\t\n")).isEmpty());
    }

    @Test
    @DisplayName("流式：无文本无工具调用的响应整条流为空（Flowable empty）")
    public void test_content_free_stream_is_empty() {
        assertTrue(collect(aggregator(null, null), textChunk("")).isEmpty());
        assertTrue(collect(aggregator(null, null)).isEmpty(), "空输入流也必须为空");
        assertTrue(aggregator(null, null).toLlmResponses(Flowable.just(new ChatResponse(List.of())))
                .toList().blockingGet().isEmpty(), "无 results 的响应不得产出聚合事件");
    }

    // ===== 流式：工具调用 =====

    @Test
    @DisplayName("流式：工具调用 chunk 不发分片，但作为 functionCall part 进入聚合事件")
    public void test_tool_call_chunk_not_partial_but_aggregated() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                toolChunk(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", "{\"command\":\"ls -l\"}")),
                textChunk("done"));

        assertEquals(2, responses.size(), "工具 chunk 不得单独成事件：1 条文本分片 + 1 条聚合");
        assertEquals("done", textOf(responses.get(0)));

        LlmResponse last = aggregated(responses);
        List<Part> calls = functionCallParts(last);
        assertEquals(1, calls.size());
        assertEquals("call-1", calls.get(0).functionCall().get().id().orElse(null));
        assertEquals("executeCommand", calls.get(0).functionCall().get().name().orElse(null));
        assertEquals("ls -l", calls.get(0).functionCall().get().args().orElse(Map.of()).get("command"));
        assertEquals("done", textOf(last), "文本与工具调用共存于同一条聚合事件");
    }

    @Test
    @DisplayName("流式：同一 id 重复出现但后一条无参数时，保留前一条的参数（provider 补发空壳不覆盖）")
    public void test_tool_call_duplicate_without_arguments_keeps_first() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                toolChunk(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", "{\"command\":\"df -h\"}")),
                toolChunk(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", null)));

        List<Part> calls = functionCallParts(aggregated(responses));
        assertEquals(1, calls.size(), "同一 id 必须合并成一条");
        assertEquals("df -h", calls.get(0).functionCall().get().args().orElse(Map.of()).get("command"),
                "无参数的重复条目不得覆盖已有参数");
    }

    @Test
    @DisplayName("流式：同一 id 的后续完整条目（带参数）覆盖前一条")
    public void test_tool_call_duplicate_with_arguments_wins() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                toolChunk(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", "{\"command\":\"partial\"}")),
                toolChunk(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", "{\"command\":\"df -h\"}")));

        List<Part> calls = functionCallParts(aggregated(responses));
        assertEquals(1, calls.size());
        assertEquals("df -h", calls.get(0).functionCall().get().args().orElse(Map.of()).get("command"));
    }

    @Test
    @DisplayName("流式：不同 id 的工具调用按出现顺序全部保留")
    public void test_tool_calls_distinct_ids_kept_in_order() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                toolChunk(
                        new AssistantMessage.ToolCall("call-a", "function", "toolA", "{\"x\":1}"),
                        new AssistantMessage.ToolCall("call-b", "function", "toolB", "{\"y\":2}")));

        List<Part> calls = functionCallParts(aggregated(responses));
        assertEquals(2, calls.size());
        assertEquals("call-a", calls.get(0).functionCall().get().id().orElse(null));
        assertEquals("call-b", calls.get(1).functionCall().get().id().orElse(null));
    }

    @Test
    @DisplayName("流式：非 function 类型的 toolCall 被忽略")
    public void test_non_function_tool_call_ignored() {
        List<LlmResponse> responses = collect(aggregator(null, null),
                toolChunk(new AssistantMessage.ToolCall("call-1", "retrieval", "search", "{}")),
                textChunk("ok"));

        assertEquals(0, functionCallParts(aggregated(responses)).size(),
                "非 function 类型的调用不应转成 FunctionCall part");
        assertEquals("ok", textOf(aggregated(responses)));
    }

    // ===== 流式：草稿与 usage =====

    @Test
    @DisplayName("流式：每个 chunk 的草稿分片累积后以隐藏标记前缀进入聚合事件")
    public void test_stream_draft_wrapped_into_aggregate() {
        ReasoningDraftAccess draftAccess = new ReasoningDraftAccess() {
            @Override
            public String readDraft(AssistantMessage message) {
                return "思";
            }
        };

        List<LlmResponse> responses = collect(aggregator(draftAccess, null), textChunk("答"), textChunk("案"));

        LlmResponse last = aggregated(responses);
        String aggregatedText = textOf(last);
        String[] parsed = ReasoningTagUtil.parse(aggregatedText);
        assertEquals("思思", parsed[0], "两个 chunk 的草稿分片必须累积");
        assertEquals("答案", parsed[1], "可见正文不含草稿");
        assertEquals("答", textOf(responses.get(0)), "分片事件只带可见正文，不带草稿标记");
    }

    @Test
    @DisplayName("流式：末块（无文本）携带的 usage 必须被聚合事件捕获成 usageMetadata")
    public void test_stream_usage_metadata_captured_from_last_chunk() {
        // 末块通常只有 usage、没有文本，会被空白过滤丢掉——所以 usage 必须在过滤前观察
        List<LlmResponse> responses = collect(aggregator(null, null),
                textChunk("结果"),
                textChunkWithUsage("", new DefaultUsage(120, 30, 150)));

        LlmResponse last = aggregated(responses);
        assertTrue(last.usageMetadata().isPresent(), "聚合事件必须带 usageMetadata（压缩引擎据此读真实 promptTokenCount）");
        assertEquals(120, last.usageMetadata().get().promptTokenCount().orElse(0));
        assertEquals(30, last.usageMetadata().get().candidatesTokenCount().orElse(0));
        assertEquals(150, last.usageMetadata().get().totalTokenCount().orElse(0));
    }

    @Test
    @DisplayName("流式：usage 为 null 时聚合事件不带 usageMetadata；默认元数据（EmptyUsage）则带全 0 计数")
    public void test_stream_usage_absent_vs_placeholder() {
        // Spring AI 的 ChatResponseMetadata 默认 usage = EmptyUsage（各计数为 0），
        // 因此"没有真实统计"表现为全 0 而不是"没有 usageMetadata"。
        List<LlmResponse> withPlaceholder = collect(aggregator(null, null), textChunk("hi"));
        assertTrue(aggregated(withPlaceholder).usageMetadata().isPresent());
        assertEquals(0, aggregated(withPlaceholder).usageMetadata().get().promptTokenCount().orElse(-1));

        // usage 显式为 null 时才真的不挂 usageMetadata
        ChatResponse noUsage = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("hi").toolCalls(List.of()).build())),
                ChatResponseMetadata.builder().usage(null).build());
        List<LlmResponse> withoutUsage = collect(aggregator(null, null), noUsage);
        assertTrue(aggregated(withoutUsage).usageMetadata().isEmpty(),
                "usage 为 null 时不得挂空的 usageMetadata");
    }

    // ===== 同步路径 =====

    @Test
    @DisplayName("同步：toAggregatedResponse 汇总文本，partial 为空")
    public void test_sync_text_aggregation() {
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("同步正文").toolCalls(List.of()).build())));

        LlmResponse aggregated = aggregator(null, null).toAggregatedResponse(response);
        assertNotNull(aggregated);
        assertEquals("同步正文", textOf(aggregated));
        assertTrue(aggregated.partial().isEmpty());
    }

    @Test
    @DisplayName("同步：既无文本、无工具调用、无草稿时返回 null（调用方转成空流）")
    public void test_sync_empty_returns_null() {
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of()).build())));

        assertNull(aggregator(null, null).toAggregatedResponse(response));
    }

    @Test
    @DisplayName("同步：仅在工具调用类型为 function 且参数合法时才产出 functionCall part")
    public void test_sync_tool_calls() {
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "toolA", "{\"a\":1}"),
                        new AssistantMessage.ToolCall("c2", "retrieval", "toolB", "{\"b\":2}")))
                .build())));

        List<Part> calls = functionCallParts(aggregator(null, null).toAggregatedResponse(response));
        assertEquals(1, calls.size(), "非 function 类型的调用被跳过");
        assertEquals("toolA", calls.get(0).functionCall().get().name().orElse(null));
    }

    @Test
    @DisplayName("同步：参数不是合法 JSON 的工具调用被跳过（不抛异常）")
    public void test_sync_invalid_json_arguments_skipped() {
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "toolA", "not-a-json")))
                .build())));

        assertNull(aggregator(null, null).toAggregatedResponse(response),
                "参数解析失败时该调用被跳过，无其它内容则整体为 null");
    }

    @Test
    @DisplayName("同步：元数据里的草稿被包成隐藏标记前缀，正文紧随其后")
    public void test_sync_draft_from_metadata() {
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(ReasoningDraftProtocol.REASONING_METADATA_KEY, "同步推理"))
                .toolCalls(List.of())
                .build())));

        LlmResponse aggregated = aggregator(null, null).toAggregatedResponse(response);
        String[] parsed = ReasoningTagUtil.parse(textOf(aggregated));
        assertEquals("同步推理", parsed[0]);
        assertEquals("正文", parsed[1]);
    }

    @Test
    @DisplayName("同步：usage 映射进 usageMetadata")
    public void test_sync_usage_metadata() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("x").toolCalls(List.of()).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(7, 3, 10)).build());

        LlmResponse aggregated = aggregator(null, null).toAggregatedResponse(response);
        assertEquals(7, aggregated.usageMetadata().get().promptTokenCount().orElse(0));
        assertEquals(10, aggregated.usageMetadata().get().totalTokenCount().orElse(0));
    }

    @Test
    @DisplayName("流式：草稿只在聚合事件里出现一次，且分片流总条数 = 文本分片数 + 1")
    public void test_stream_exactly_one_aggregate_with_draft_and_usage() {
        ReasoningDraftAccess draftAccess = new ReasoningDraftAccess() {
            @Override
            public String readDraft(AssistantMessage message) {
                return message.getText().isEmpty() ? "草稿" : null;
            }
        };

        List<LlmResponse> responses = collect(aggregator(draftAccess, null),
                textChunk("A"),
                textChunkWithUsage("", new DefaultUsage(5, 1, 6)),
                textChunk("B"));

        assertEquals(3, responses.size(), "2 条分片 + 1 条聚合");
        assertEquals(2, partials(responses).size());
        assertFalse(responses.get(responses.size() - 1).partial().orElse(false));
        assertTrue(aggregated(responses).usageMetadata().isPresent());
        assertEquals("AB", ReasoningTagUtil.parse(textOf(aggregated(responses)))[1]);
    }
}

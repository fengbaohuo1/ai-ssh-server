package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentResultExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 子智能体结果收敛（SubAgentResultExtractor）单元测试——纯函数。
 *
 * <p>覆盖：取最后一个有文本的聚合事件、跳过流式分片、跳过纯工具调用事件、
 * 全分片异常流的兜底、空事件流。
 */
public class SubAgentResultExtractorTest {

    private Event textEvent(String text, boolean partial) {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshDiagnosisAgent")
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .partial(partial)
                .build();
    }

    /** 纯工具调用事件（无文本）——模拟一次 functionCall 事件 */
    private Event toolCallEvent() {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshDiagnosisAgent")
                .content(Content.builder().role("model").parts(List.of(
                        Part.builder().functionCall(com.google.genai.types.FunctionCall.builder()
                                .id("call-1").name("executeCommand").build()).build())).build())
                .build();
    }

    @Test
    public void test_pick_last_text_event_skipping_partials() {
        List<Event> events = List.of(
                textEvent("思考中…", true),
                textEvent("磁盘占用 80%", false),
                textEvent("碎片", true));
        assertEquals("磁盘占用 80%", SubAgentResultExtractor.pickFinalText(events));
    }

    @Test
    public void test_skip_trailing_tool_call_event() {
        List<Event> events = List.of(
                textEvent("先看看磁盘", false),
                toolCallEvent());
        // 末事件是纯工具调用（无文本）→ 前推到最后一个有文本的事件
        assertEquals("先看看磁盘", SubAgentResultExtractor.pickFinalText(events));
    }

    @Test
    public void test_all_partial_fallback_to_last_text() {
        List<Event> events = List.of(
                textEvent("第一段", true),
                textEvent("第二段", true));
        assertEquals("第二段", SubAgentResultExtractor.pickFinalText(events));
    }

    @Test
    public void test_empty_inputs_return_blank() {
        assertEquals("", SubAgentResultExtractor.pickFinalText(null));
        assertEquals("", SubAgentResultExtractor.pickFinalText(List.of()));
        assertEquals("", SubAgentResultExtractor.pickFinalText(List.of(toolCallEvent())));
    }
}

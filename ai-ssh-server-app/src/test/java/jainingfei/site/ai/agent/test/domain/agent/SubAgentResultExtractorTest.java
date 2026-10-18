package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentResultExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ═══════════════════════════════════════════════════════════════
    //  执行痕迹（父智能体据此避免重复执行子任务命令）
    // ═══════════════════════════════════════════════════════════════

    /** 带 command 参数的 executeCommand 调用事件 */
    private Event commandCallEvent(String callId, String command) {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshDiagnosisAgent")
                .content(Content.builder().role("model").parts(List.of(
                        Part.builder().functionCall(FunctionCall.builder()
                                .id(callId).name("executeCommand")
                                .args(Map.of("command", command)).build()).build())).build())
                .build();
    }

    /** 工具结果事件（response 直接给 map） */
    private Event functionResponseEvent(String callId, Map<String, Object> response) {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshDiagnosisAgent")
                .content(Content.builder().role("user").parts(List.of(
                        Part.builder().functionResponse(FunctionResponse.builder()
                                .id(callId).name("executeCommand").response(response).build()).build())).build())
                .build();
    }

    @Test
    public void test_command_trace_marks_success_failure_and_missing() {
        List<Event> events = List.of(
                commandCallEvent("call-1", "uptime"),
                functionResponseEvent("call-1", Map.of("success", true, "exitStatus", 0)),
                commandCallEvent("call-2", "systemctl status nginx"),
                functionResponseEvent("call-2", Map.of("success", false, "error", "Unit nginx.service not found")),
                commandCallEvent("call-3", "df -h"));
        String trace = SubAgentResultExtractor.commandTrace(events);
        assertTrue(trace.contains(SubAgentResultExtractor.TRACE_HEADER), trace);
        assertTrue(trace.contains("uptime → 成功"), trace);
        assertTrue(trace.contains("systemctl status nginx → 失败"), trace);
        assertTrue(trace.contains("Unit nginx.service not found"), trace);
        assertTrue(trace.contains("df -h → 未返回结果"), trace);
        assertTrue(trace.contains("共 3 条"), trace);
    }

    @Test
    public void test_command_trace_empty_without_calls() {
        assertEquals("", SubAgentResultExtractor.commandTrace(null));
        assertEquals("", SubAgentResultExtractor.commandTrace(List.of()));
        assertEquals("", SubAgentResultExtractor.commandTrace(List.of(textEvent("结论", false))));
    }

    @Test
    public void test_append_command_trace_keeps_conclusion_and_trace() {
        String combined = SubAgentResultExtractor.appendCommandTrace("磁盘快满了", List.of(
                commandCallEvent("call-1", "df -h"),
                functionResponseEvent("call-1", Map.of("success", true))));
        assertTrue(combined.startsWith("磁盘快满了"), combined);
        assertTrue(combined.contains("[子智能体执行痕迹]"), combined);
        assertTrue(combined.contains("df -h → 成功"), combined);
        // 无痕迹时不改变结论
        assertEquals("只有结论", SubAgentResultExtractor.appendCommandTrace("只有结论", List.of()));
    }

    @Test
    public void test_command_trace_caps_calls_and_length() {
        List<Event> events = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            events.add(commandCallEvent("call-" + i, "echo " + i));
        }
        String trace = SubAgentResultExtractor.commandTrace(events);
        assertTrue(trace.contains("其余 15 条已省略"), trace);
        assertTrue(trace.length() <= SubAgentResultExtractor.MAX_TRACE_CHARS + 200, "trace too long: " + trace.length());
    }
}

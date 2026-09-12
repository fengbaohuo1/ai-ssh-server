package jainingfei.site.ai.agent.test.domain.agent.session;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.EventGovernor;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件治理器（EventGovernor）单元测试——纯函数式净化与限长，无网络无 Spring。
 *
 * <p>重点回归：<b>角色解析必须结构化优先</b>。ADK 的 {@code Functions.buildResponseEvent}
 * 把工具结果事件的 {@code content.role} 设为 {@code "user"}，只按 role 判断会把工具结果
 * 送进 user 净化分支，一旦超长就被重建成纯文本，FunctionResponse part 丢失，
 * 下一轮历史重建凑不出 tool 消息与 assistant tool_calls 的配对，provider 直接 400。
 */
public class EventGovernorTest {

    private SessionGovernanceProperties properties;
    private EventGovernor governor;

    @BeforeEach
    public void setUp() {
        properties = new SessionGovernanceProperties();
        properties.setMaxUserText(50);
        properties.setMaxToolText(120);
        properties.setMaxAssistantText(80);
        properties.setMaxTurns(2);
        properties.setMaxEvents(6);
        governor = new EventGovernor(properties);
    }

    // ===== 构造事件 =====

    private Event textEvent(String role, String author, String text) {
        return Event.builder()
                .invocationId("inv-1")
                .author(author)
                .content(Content.builder().role(role).parts(List.of(Part.fromText(text))).build())
                .build();
    }

    private Event functionResponseEvent(String id, Map<String, Object> response) {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshOperator")
                .content(Content.builder().role("user")
                        .parts(List.of(Part.builder().functionResponse(FunctionResponse.builder()
                                .id(id).name("executeCommand").response(response).build()).build()))
                        .build())
                .build();
    }

    private Event functionCallEvent(String text) {
        return Event.builder()
                .invocationId("inv-1")
                .author("sshOperator")
                .content(Content.builder().role("model")
                        .parts(List.of(
                                Part.fromText(text),
                                Part.builder().functionCall(FunctionCall.builder()
                                        .id("call-1").name("executeCommand")
                                        .args(Map.of("command", "df -h")).build()).build()))
                        .build())
                .build();
    }

    private static String storedText(Event event) {
        return event.content().flatMap(Content::parts).orElse(List.of()).stream()
                .map(p -> p.text().orElse(""))
                .reduce("", String::concat);
    }

    // ===== resolveRole =====

    @Test
    @DisplayName("resolveRole 结构化优先：role=user 但带 FunctionResponse part 的事件解析为 tool")
    public void test_resolve_role_function_response_wins_over_role() {
        Event event = functionResponseEvent("call-1", Map.of("result", "ok"));

        assertEquals("tool", governor.resolveRole(event),
                "工具结果事件必须解析成 tool，否则超长时会被重建成纯文本、配对断裂 400");
    }

    @Test
    @DisplayName("resolveRole：带 FunctionCall part 的事件解析为 model（不论 content.role 是什么）")
    public void test_resolve_role_function_call() {
        Event event = Event.builder()
                .invocationId("inv-1").author("user")
                .content(Content.builder().role("user")
                        .parts(List.of(Part.builder().functionCall(FunctionCall.builder()
                                .id("c").name("t").args(Map.of()).build()).build()))
                        .build())
                .build();

        assertEquals("model", governor.resolveRole(event));
    }

    @Test
    @DisplayName("resolveRole：纯文本事件按 content.role 判定")
    public void test_resolve_role_by_content_role() {
        assertEquals("user", governor.resolveRole(textEvent("user", "user", "你好")));
        assertEquals("model", governor.resolveRole(textEvent("model", "sshOperator", "回复")));
    }

    @Test
    @DisplayName("resolveRole：content 无 role 时回退 author；无 content 时也用 author")
    public void test_resolve_role_author_fallback() {
        Event noRole = Event.builder().invocationId("inv").author("user")
                .content(Content.builder().parts(List.of(Part.fromText("文本"))).build())
                .build();
        assertEquals("user", governor.resolveRole(noRole));

        Event noContent = Event.builder().invocationId("inv").author("sshOperator").build();
        assertEquals("sshOperator", governor.resolveRole(noContent));
    }

    @Test
    @DisplayName("isAssistantRole：assistant / model 大小写不敏感，其它角色为 false")
    public void test_is_assistant_role() {
        assertTrue(EventGovernor.isAssistantRole("assistant"));
        assertTrue(EventGovernor.isAssistantRole("Assistant"));
        assertTrue(EventGovernor.isAssistantRole("model"));
        assertTrue(EventGovernor.isAssistantRole("MODEL"));
        assertFalse(EventGovernor.isAssistantRole("user"));
        assertFalse(EventGovernor.isAssistantRole("tool"));
        assertFalse(EventGovernor.isAssistantRole(null));
    }

    // ===== governUserEvent =====

    @Test
    @DisplayName("governUserEvent：剥离分隔线之后的动态注入段，只留原始用户问题")
    public void test_govern_user_strips_dynamic_suffix() {
        String enriched = "请继续查看 error.log\n---\n[系统环境]\n系统: Linux\n[最近执行的命令]\n$ tail -n 50 error.log";
        Event governed = governor.governUserEvent(textEvent("user", "user", enriched));

        assertEquals("请继续查看 error.log", storedText(governed));
    }

    @Test
    @DisplayName("governUserEvent：分隔线之后不是动态段时不误切（用户原文自带分隔线）")
    public void test_govern_user_keeps_user_separator() {
        String text = "看看这段输出\n---\n这是用户自己写的第二段";
        Event event = textEvent("user", "user", text);

        assertSame(event, governor.governUserEvent(event), "未发生净化时应原样返回同一实例");
        assertEquals(text, storedText(event));
    }

    @Test
    @DisplayName("governUserEvent：无分隔线但以动态段标签结尾时按启发式切掉最后一行")
    public void test_govern_user_heuristic_suffix() {
        Event governed = governor.governUserEvent(textEvent("user", "user", "用户问题\n[工具执行摘要]"));

        assertEquals("用户问题", storedText(governed));
    }

    @Test
    @DisplayName("governUserEvent：超长文本按 maxUserText 截断并加省略号")
    public void test_govern_user_truncates() {
        Event governed = governor.governUserEvent(textEvent("user", "user", "x".repeat(200)));
        String stored = storedText(governed);

        assertEquals(properties.getMaxUserText() + 3, stored.length(), "截断长度 = 上限 + \"...\"");
        assertTrue(stored.endsWith("..."));
    }

    @Test
    @DisplayName("governUserEvent：短文本且无动态段时原样返回")
    public void test_govern_user_noop() {
        Event event = textEvent("user", "user", "简单问题");
        assertSame(event, governor.governUserEvent(event));
    }

    // ===== governToolEvent =====

    @Test
    @DisplayName("governToolEvent：FunctionResponse 超长时保留 part 结构，只压缩 response 字符串")
    public void test_govern_tool_keeps_part_structure() {
        String longResult = "A".repeat(2000);
        Event event = functionResponseEvent("call-9", Map.of("success", true, "result", longResult));

        Event governed = governor.governToolEvent(event);
        Part part = governed.content().flatMap(Content::parts).orElse(List.of()).stream()
                .filter(p -> p.functionResponse().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("FunctionResponse part 被破坏"));

        assertEquals("call-9", part.functionResponse().get().id().orElse(null), "tool_call_id 必须保留");
        assertEquals("executeCommand", part.functionResponse().get().name().orElse(null));
        Object result = part.functionResponse().get().response().orElse(Map.of()).get("result");
        assertTrue(result instanceof String value && value.length() < longResult.length(),
                "超预算时响应字符串应被 head/tail 限长");
        assertFalse(storedText(governed).startsWith("Function Response:"), "不得被 stringify 成纯文本");
    }

    @Test
    @DisplayName("governToolEvent：短响应内容原样保留；预算充足时连事件实例都不重建")
    public void test_govern_tool_small_response_untouched() {
        Event event = functionResponseEvent("call-1", Map.of("result", "ok"));

        Part part = governor.governToolEvent(event)
                .content().flatMap(Content::parts).orElse(List.of()).stream()
                .filter(p -> p.functionResponse().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("FunctionResponse part 丢失"));
        assertEquals("call-1", part.functionResponse().get().id().orElse(null));
        assertEquals("ok", part.functionResponse().get().response().orElse(Map.of()).get("result"));

        // 预算远大于事件串长度时必须是同一个实例（不做无谓重建）
        properties.setMaxToolText(2000);
        Event shortEvent = functionResponseEvent("call-2", Map.of("result", "ok"));
        assertSame(shortEvent, governor.governToolEvent(shortEvent));
    }

    @Test
    @DisplayName("governToolEvent：无 FunctionResponse 的超长文本走 head/tail 限长（保留头尾信息）")
    public void test_govern_tool_plain_text_head_tail() {
        Event event = textEvent("tool", "sshOperator", "HEAD-" + "M".repeat(1000) + "-TAIL");
        Event governed = governor.governToolEvent(event);
        String stored = storedText(governed);

        assertTrue(stored.length() < 1000, "必须被限长，实际长度 " + stored.length());
        assertTrue(stored.startsWith("HEAD-"), stored.substring(0, 20));
        assertTrue(stored.endsWith("-TAIL"), "尾部信息（最终结论/报错）必须保留");
        assertTrue(stored.contains("省略"), "必须带省略标记，让模型知道内容被截断过");
    }

    // ===== governAssistantEvent =====

    @Test
    @DisplayName("governAssistantEvent：草稿标记区不参与截断，解析回来仍是完整草稿")
    public void test_govern_assistant_marker_survives_truncation() {
        String reasoning = "这是一段需要原样回放的推理内容";
        Event event = textEvent("model", "sshOperator",
                ReasoningTagUtil.wrap(reasoning) + "长正文".repeat(200));

        Event governed = governor.governAssistantEvent(event);
        String[] parsed = ReasoningTagUtil.parse(storedText(governed));

        assertEquals(reasoning, parsed[0], "隐藏标记被截断会导致下轮无法解析草稿");
        assertTrue(parsed[1].length() < 800, "可见正文必须被限长");
        assertTrue(parsed[1].contains("省略"), parsed[1].substring(0, Math.min(60, parsed[1].length())));
    }

    @Test
    @DisplayName("governAssistantEvent：带 FunctionCall 时只限长文本 part，工具调用 part 原样保留")
    public void test_govern_assistant_keeps_function_call() {
        Event event = functionCallEvent("长正文".repeat(200));

        Event governed = governor.governAssistantEvent(event);
        List<Part> parts = governed.content().flatMap(Content::parts).orElse(List.of());

        assertEquals(2, parts.size(), "文本 part + 工具调用 part");
        Part callPart = parts.stream().filter(p -> p.functionCall().isPresent()).findFirst()
                .orElseThrow(() -> new AssertionError("FunctionCall part 丢失，tool 配对会断裂"));
        assertEquals("call-1", callPart.functionCall().get().id().orElse(null));
        assertEquals("executeCommand", callPart.functionCall().get().name().orElse(null));
        assertEquals("df -h", callPart.functionCall().get().args().orElse(Map.of()).get("command"));
        assertTrue(storedText(governed).length() < 800, "文本部分必须被限长");
    }

    @Test
    @DisplayName("governAssistantEvent：不超限的事件原样返回")
    public void test_govern_assistant_noop() {
        Event event = textEvent("model", "sshOperator", "短回复");
        assertSame(event, governor.governAssistantEvent(event));
    }

    @Test
    @DisplayName("governAssistantEvent：文本很短但事件串里含工具调用渲染时，FunctionCall part 仍完好")
    public void test_govern_assistant_short_text_with_call() {
        Event event = functionCallEvent("好的，我执行一下。");
        Event governed = governor.governAssistantEvent(event);

        Part callPart = governed.content().flatMap(Content::parts).orElse(List.of()).stream()
                .filter(p -> p.functionCall().isPresent()).findFirst()
                .orElseThrow(() -> new AssertionError("FunctionCall part 丢失，tool 配对会断裂"));
        assertEquals("call-1", callPart.functionCall().get().id().orElse(null));
        assertEquals("executeCommand", callPart.functionCall().get().name().orElse(null));
    }
}

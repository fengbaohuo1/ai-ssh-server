package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话治理 × 工具配对回归测试。
 *
 * <p>回归场景（2-10 冒烟实测踩到）：ADK 把工具结果事件的 {@code content.role} 设为 <b>"user"</b>，
 * 若按 role 判断，超长工具结果会被当成"用户消息"重建成纯文本——FunctionResponse part 丢失，
 * 下一轮历史重建凑不出 tool 消息与 assistant tool_calls 的配对，provider 直接返回 400：
 * <pre>
 *   "An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'."
 * </pre>
 * 因此 {@code resolveRole} 必须结构化优先：带 FunctionResponse 的事件永远是工具结果，
 * 超预算时只做 response map 字符串的 head/tail 限长，<b>绝不破坏 part 结构</b>。
 */
public class SessionToolPairingTest {

    private CustomAdkSessionService sessionService;

    @BeforeEach
    public void setUp() throws Exception {
        sessionService = new CustomAdkSessionService();
        // 直接构造的实例没有 Spring 注入，按典型配置手工赋值（小上限便于触发截断分支）
        setField("maxEvents", 20);
        setField("maxAssistantText", 200);
        setField("maxToolText", 300);
        setField("maxUserText", 200);
        setField("maxTurns", 4);
        setField("compactEnabled", false);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = CustomAdkSessionService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(sessionService, value);
    }

    private Session session() {
        sessionService.createSession("sshAgent", "u1", new ConcurrentHashMap<>(), "s1").blockingGet();
        return sessionService.getSession("sshAgent", "u1", "s1", Optional.empty()).blockingGet();
    }

    private Event functionResponseEvent(String id, Map<String, Object> response) {
        // 与 ADK Functions.buildResponseEvent 保持一致：role=user + FunctionResponse part
        return Event.builder()
                .invocationId("inv-1")
                .author("sshOperator")
                .content(Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().functionResponse(FunctionResponse.builder()
                                .id(id).name("dispatchSubAgents").response(response).build()).build()))
                        .build())
                .build();
    }

    private Part lastFunctionResponsePart(Session session) {
        return session.events().get(session.events().size() - 1)
                .content().flatMap(Content::parts).orElse(List.of()).stream()
                .filter(p -> p.functionResponse().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具结果事件的 FunctionResponse part 丢失（配对被破坏）"));
    }

    @Test
    public void test_oversized_function_response_keeps_part_structure() {
        Session session = session();
        String longResult = "A".repeat(3000);
        Event event = functionResponseEvent("call-1", Map.of("success", true, "result", longResult));

        sessionService.appendEvent(session, event).blockingGet();

        Event stored = session.events().get(session.events().size() - 1);
        Part part = lastFunctionResponsePart(session);
        assertEquals("call-1", part.functionResponse().get().id().orElse(null), "tool_call_id 必须保留");
        assertEquals("dispatchSubAgents", part.functionResponse().get().name().orElse(null));
        Object result = part.functionResponse().get().response().orElse(Map.of()).get("result");
        assertTrue(result instanceof String value && value.length() < longResult.length(),
                "超预算时响应字符串应被 head/tail 限长");
        assertTrue(result.toString().length() > 0);
        // 不能被重建成纯文本事件
        assertTrue(stored.content().get().parts().get().stream()
                .noneMatch(p -> p.text().isPresent() && p.text().get().startsWith("Function Response:")),
                "工具结果不应被 stringify 成纯文本");
    }

    @Test
    public void test_small_function_response_untouched() {
        Session session = session();
        sessionService.appendEvent(session, functionResponseEvent("call-2", Map.of("success", true, "result", "ok")))
                .blockingGet();

        Part part = lastFunctionResponsePart(session);
        assertEquals("ok", part.functionResponse().get().response().orElse(Map.of()).get("result"));
    }

    @Test
    public void test_user_text_still_sanitized() {
        Session session = session();
        String longUserText = "请继续分析 " + "x".repeat(500);
        sessionService.appendEvent(session, Event.builder()
                .invocationId("inv-1")
                .author("user")
                .content(Content.builder().role("user").parts(List.of(Part.fromText(longUserText))).build())
                .build()).blockingGet();

        String stored = session.events().get(session.events().size() - 1)
                .content().flatMap(Content::parts).orElse(List.of()).get(0).text().orElse("");
        assertTrue(stored.length() <= 200 + 3, "用户消息仍按上限截断，实际长度 " + stored.length());
    }
}

package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.support.ReActStepSink;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合步骤出口（ReActStepSink）单元测试——纯内存，无网络无 Spring。
 *
 * <p>它是一条跨层协议（子智能体在 domain 工具层执行、SSE emitter 在 case 层），
 * 因此必须固定住：按会话隔离、回合结束即解绑、出口抛错绝不反噬正在执行的回合。
 */
public class ReActStepSinkTest {

    private static final String SESSION = "sink-test-session";

    @AfterEach
    public void tearDown() {
        ReActStepSink.unbind(SESSION);
    }

    @Test
    @DisplayName("绑定后可按会话取回出口，步骤原样送达")
    public void test_bind_and_emit() {
        List<Map<String, Object>> captured = new ArrayList<>();
        ReActStepSink.bind(SESSION, captured::add);

        ReActStepSink sink = ReActStepSink.of(SESSION);
        assertSame(sink, ReActStepSink.of(SESSION), "同一会话取回同一出口");
        sink.emit(StepEvents.toolCall("c1", "executeCommand", Map.of("command", "ls"), null, null));

        assertTrue(captured.size() == 1, "捕获到一条步骤");
        assertTrue("tool_call".equals(captured.get(0).get(StepEvents.F_TYPE)));
    }

    @Test
    @DisplayName("未绑定的会话取回 null，emit 是空操作")
    public void test_unbound_session_is_null() {
        assertNull(ReActStepSink.of("never-bound-session"));
        assertNull(ReActStepSink.from(null), "工具上下文缺失时返回 null（工具被直接调用的场景）");
    }

    @Test
    @DisplayName("解绑后不再取回出口（回合收口必须清理，避免常驻内存堆积）")
    public void test_unbind() {
        ReActStepSink.bind(SESSION, step -> {
        });
        assertTrue(ReActStepSink.of(SESSION) != null);

        ReActStepSink.unbind(SESSION);
        assertNull(ReActStepSink.of(SESSION));
        assertDoesNotThrow(() -> ReActStepSink.unbind(SESSION), "重复解绑不报错");
    }

    @Test
    @DisplayName("出口抛错不反噬回合，空步骤不下发")
    public void test_emit_never_throws() {
        List<Map<String, Object>> captured = new ArrayList<>();
        ReActStepSink.bind(SESSION, step -> {
            captured.add(step);
            throw new IllegalStateException("前端已断开");
        });

        ReActStepSink sink = ReActStepSink.of(SESSION);
        assertDoesNotThrow(() -> sink.emit(StepEvents.error("boom")));
        assertDoesNotThrow(() -> sink.emit(null));
        assertDoesNotThrow(() -> sink.emit(Map.of()));
        assertTrue(captured.size() == 1, "只有非空步骤会被送达");
    }

    @Test
    @DisplayName("绑定参数为 null 时安全忽略（同步 chat / 无 emitter 场景）")
    public void test_bind_null_safe() {
        assertDoesNotThrow(() -> ReActStepSink.bind(null, step -> {
        }));
        assertDoesNotThrow(() -> ReActStepSink.bind(SESSION, null));
        assertNull(ReActStepSink.of(null));
    }
}

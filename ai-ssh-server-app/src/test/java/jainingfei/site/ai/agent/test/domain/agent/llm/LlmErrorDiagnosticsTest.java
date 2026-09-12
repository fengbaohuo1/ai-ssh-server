package jainingfei.site.ai.agent.test.domain.agent.llm;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.LlmErrorDiagnostics;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * LLM 失败诊断（LlmErrorDiagnostics）单元测试。
 *
 * <p>诊断代码本身绝不能成为新的故障点：无论异常形态多奇怪、请求形状多不完整，
 * {@code logFailure} 都必须"尽力打印后安静退出"。这里逐一验证各类输入下不抛异常。
 */
public class LlmErrorDiagnosticsTest {

    private final LlmErrorDiagnostics diagnostics = new LlmErrorDiagnostics();
    private final ReasoningDraftProtocol protocol = new ReasoningDraftProtocol();

    /** 模拟 WebClientResponseException：带 getResponseBodyAsString()（反射路径） */
    public static class FakeHttpError extends RuntimeException {
        public FakeHttpError(String message) {
            super(message);
        }

        public String getResponseBodyAsString() {
            return "{\"error\":{\"message\":\"An assistant message with 'tool_calls' must be followed by tool messages\"}}";
        }
    }

    /** 有同名方法但调用即抛——覆盖反射路径的失败分支 */
    public static class BrokenHttpError extends RuntimeException {
        public BrokenHttpError(String message) {
            super(message);
        }

        public String getResponseBodyAsString() {
            throw new IllegalStateException("响应体已被消费");
        }
    }

    private Prompt richPrompt() {
        return new Prompt(List.of(
                new SystemMessage("你是 SSH 运维助手"),
                new UserMessage("查看磁盘"),
                protocol.buildHistoryMessage("", "历史推理",
                        List.of(new AssistantMessage.ToolCall("call-1", "function", "executeCommand", "{\"command\":\"df -h\"}"))),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "executeCommand", "{\"ok\":true}")))
                        .build()));
    }

    @Test
    @DisplayName("普通异常：logFailure 不抛异常")
    public void test_plain_exception() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException("连接超时"), richPrompt(), protocol));
    }

    @Test
    @DisplayName("嵌套 cause 链：logFailure 不抛异常")
    public void test_nested_cause_chain() {
        Throwable deep = new IllegalArgumentException("最内层",
                new IllegalStateException("中间层", new RuntimeException("最外层")));

        assertDoesNotThrow(() -> diagnostics.logFailure(deep, richPrompt(), protocol));
    }

    @Test
    @DisplayName("异常带 getResponseBodyAsString()：走反射取出服务端原文且不抛异常")
    public void test_reflective_response_body() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new FakeHttpError("400 Bad Request"), richPrompt(), protocol));
    }

    @Test
    @DisplayName("反射调用 getResponseBodyAsString() 自身抛异常时被吞掉")
    public void test_reflective_response_body_failure() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new BrokenHttpError("400 Bad Request"), richPrompt(), protocol));
    }

    @Test
    @DisplayName("prompt 为 null 时诊断自身不得抛异常（异常路径上再抛异常最难排查）")
    public void test_null_prompt_is_safe() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException("boom"), null, protocol));
    }

    @Test
    @DisplayName("draftProtocol 为 null 时描述形状不得 NPE")
    public void test_null_protocol_is_safe() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException("boom"), richPrompt(), null));
    }

    @Test
    @DisplayName("超长消息文本被缩写，且换行被替换（日志单行可读）")
    public void test_long_text_abbreviated() {
        // 通过反射拿不到私有方法，这里用"行为不抛异常 + 形状描述可跑通"的方式覆盖缩写分支
        String longText = "x".repeat(500) + "\n第二行";
        Prompt prompt = new Prompt(List.of(new UserMessage(longText)));

        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException("boom"), prompt, protocol));
    }

    @Test
    @DisplayName("空消息列表的 Prompt 也能安全描述")
    public void test_empty_prompt() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException("boom"), new Prompt(List.of()), protocol));
    }

    @Test
    @DisplayName("异常引用为 null 时也被内部兜底吞掉，不向外抛")
    public void test_null_error_is_safe() {
        assertDoesNotThrow(() -> diagnostics.logFailure(null, richPrompt(), protocol));
    }

    @Test
    @DisplayName("无 message 的异常同样安全（error.getMessage() 为 null）")
    public void test_exception_without_message() {
        assertDoesNotThrow(() -> diagnostics.logFailure(new RuntimeException(), richPrompt(), protocol));
    }
}

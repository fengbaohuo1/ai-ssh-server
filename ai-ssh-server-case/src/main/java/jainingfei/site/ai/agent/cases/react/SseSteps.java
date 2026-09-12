package jainingfei.site.ai.agent.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 发射工具——把 {@link StepEvents} 造出来的步骤写成前端信封（case 层唯一负责"怎么发"的地方）。
 *
 * <p>两种信封（前端都认）：
 * <pre>
 *   {"v":"文本增量"}                 纯文本事件
 *   {"v":"","s":{...}}               纯步骤事件
 *   {"v":"文本增量","s":[{...}]}     文本 + 步骤（同一条消息里带一个工具调用）
 * </pre>
 *
 * <p>为什么抽成静态工具：发射点不止一处——节点层（`AbstractAIAgentReActSupport` 的子类）与
 * 回合入口（{@code AIAgentReActServiceCase}，不是节点，但异常时也必须让前端看到错误）。
 * 早先这些方法只挂在节点基类上，于是"出错要发 error"这件事在入口处根本发不出来。
 */
@Slf4j
public final class SseSteps {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private SseSteps() {
    }

    /** 发一条原始 data（异常只记日志：SSE 通道断了不该反过来打断回合） */
    public static void emit(SseEmitter emitter, String data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 纯文本事件 */
    public static void sendText(SseEmitter emitter, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        emit(emitter, textPayload(content));
    }

    /** 纯步骤事件 */
    public static void sendStep(SseEmitter emitter, Map<String, Object> step) {
        if (step == null || step.isEmpty()) {
            return;
        }
        emit(emitter, stepPayload(step));
    }

    /** 文本信封：{@code {"v":"文本增量"}} */
    public static String textPayload(String content) {
        return "{\"v\":" + json(content) + "}";
    }

    /** 步骤信封：{@code {"v":"","s":{...}}} */
    public static String stepPayload(Map<String, Object> step) {
        return "{\"v\":\"\",\"s\":" + json(step) + "}";
    }

    /** 初始 thinking 事件（回合已受理） */
    public static void sendThinking(SseEmitter emitter) {
        sendStep(emitter, StepEvents.thinking());
    }

    /** 结束事件（带 stopReason，前端据此区分正常结束与中断） */
    public static void sendDone(SseEmitter emitter, String stopReason) {
        sendStep(emitter, StepEvents.done(stopReason));
        log.debug("SSE done 已发送 stopReason={}", stopReason);
    }

    /** 错误事件（前端必须能看到原因，而不是流突然结束） */
    public static void sendError(SseEmitter emitter, String message) {
        sendStep(emitter, StepEvents.error(message == null || message.isBlank() ? "未知错误" : message));
    }

    /** 本回合 token / 缓存命中累计（usage 缺失时不下发） */
    public static void sendUsage(SseEmitter emitter, long promptTokens, long completionTokens,
                                 long totalTokens, long cachedTokens) {
        if (totalTokens <= 0 && promptTokens <= 0 && completionTokens <= 0) {
            return;
        }
        sendStep(emitter, StepEvents.usage(promptTokens, completionTokens, totalTokens, cachedTokens));
    }

    /** 对象 → JSON 字符串（失败时退化为空对象，绝不抛给调用方） */
    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("步骤序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}

package jainingfei.site.ai.agent.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 已取消/已断开的 emitter → 取消原因（回合末由 release 清理，避免残留引用） */
    private static final Map<SseEmitter, String> CANCELLED = new ConcurrentHashMap<>();

    /** emitter → 取消回调（AiCallNode 注册，用于 dispose 上游 LLM 事件流） */
    private static final Map<SseEmitter, Runnable> CANCEL_HOOKS = new ConcurrentHashMap<>();

    private SseSteps() {
    }

    /**
     * 发一条原始 data。
     *
     * <p>发送失败（客户端断开/通道已关闭）不再静默：标记该 emitter 已取消并触发取消回调，
     * 由 AiCallNode dispose 本轮 Runner 事件流——否则客户端已经走了，后台还在白烧模型与工具调用。
     *
     * @return 是否发送成功
     */
    public static boolean emit(SseEmitter emitter, String data) {
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().data(data));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开，取消本回合）: {}", e.getMessage());
            cancel(emitter, "SSE 发送失败（客户端可能已断开）: " + e.getMessage());
            return false;
        }
    }

    /**
     * 标记 emitter 已取消/已断开，并触发取消回调（幂等，可重复调用）。
     *
     * <p>取消入口：发送失败、心跳失败、onTimeout、onError；取消后回合内不再继续消耗模型/工具。
     */
    public static void cancel(SseEmitter emitter, String reason) {
        if (emitter == null) {
            return;
        }
        String finalReason = reason == null || reason.isBlank() ? "客户端已断开" : reason;
        CANCELLED.putIfAbsent(emitter, finalReason);
        Runnable hook = CANCEL_HOOKS.get(emitter);
        if (hook != null) {
            try {
                hook.run();
            } catch (Exception e) {
                log.debug("SSE 取消回调执行失败: {}", e.getMessage());
            }
        }
    }

    /** 本回合是否已取消/已断开 */
    public static boolean isCancelled(SseEmitter emitter) {
        return emitter != null && CANCELLED.containsKey(emitter);
    }

    /** 取消原因（未取消返回 null） */
    public static String cancellationReason(SseEmitter emitter) {
        return emitter == null ? null : CANCELLED.get(emitter);
    }

    /** 注册取消回调（同一 emitter 只保留一个；回合结束务必 clearCancelHook/release） */
    public static void setCancelHook(SseEmitter emitter, Runnable hook) {
        if (emitter != null && hook != null) {
            CANCEL_HOOKS.put(emitter, hook);
        }
    }

    /** 清理取消回调（回合收口/异常路径） */
    public static void clearCancelHook(SseEmitter emitter) {
        if (emitter != null) {
            CANCEL_HOOKS.remove(emitter);
        }
    }

    /** 回合结束清理取消状态（emitter 键不残留） */
    public static void release(SseEmitter emitter) {
        if (emitter != null) {
            CANCELLED.remove(emitter);
            CANCEL_HOOKS.remove(emitter);
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

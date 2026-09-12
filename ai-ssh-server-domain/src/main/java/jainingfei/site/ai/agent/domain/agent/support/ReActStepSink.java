package jainingfei.site.ai.agent.domain.agent.support;

import com.google.adk.tools.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合步骤出口——"这次对话的步骤该发到哪个前端"的唯一出处（跨层协议，与 {@link SshSessionBindings} 同形）。
 *
 * <p>为什么需要它：子 Runner 的调用发生在 <b>domain 的工具层</b>（{@code SubAgentDispatchService}），
 * 而 SSE emitter 握在 <b>case 层的节点</b>手里。要让"子智能体执行了哪条命令"出现在界面上，
 * 必须有人把 emitter 的能力（"发一条步骤"）传下去。做法是照抄已有先例：
 * <pre>
 *   SshSessionBindings   会话 → connectionId（终端会话在哪条连接上执行）
 *   ReActStepSink        会话 → 步骤出口（这次回合的步骤发到哪个 emitter）
 * </pre>
 * 两者都由 case 层在回合开始时绑定、回合收口时解绑，key 都是 ADK 会话 ID——
 * 与"命令在哪个线程执行"彻底解耦（子任务跑在线程池里，ThreadLocal 没有可见性保证）。
 *
 * <p>出口本身只是一个 {@code Consumer<Map<String,Object>>}：绑定时构造步骤 JSON 并写 SSE，
 * 传入的 step 形状见 {@link StepEvents}。未绑定（同步 chat、单测、工具被直接调用）时返回 {@code null}，
 * 调用方按"没有界面可发"降级——派发链路的可用性优先于可观测性。
 */
@Slf4j
public final class ReActStepSink {

    /** sessionId → 步骤出口 */
    private static final ConcurrentHashMap<String, ReActStepSink> SINKS = new ConcurrentHashMap<>();

    private final String sessionId;
    private final java.util.function.Consumer<Map<String, Object>> consumer;

    private ReActStepSink(String sessionId, java.util.function.Consumer<Map<String, Object>> consumer) {
        this.sessionId = sessionId;
        this.consumer = consumer;
    }

    /** 绑定会话的步骤出口（RootNode 在 runAsync 之前调用；重复绑定覆盖） */
    public static void bind(String sessionId, java.util.function.Consumer<Map<String, Object>> consumer) {
        if (sessionId == null || consumer == null) {
            return;
        }
        SINKS.put(sessionId, new ReActStepSink(sessionId, consumer));
        log.debug("绑定步骤出口: sessionId={}", sessionId);
    }

    /** 解绑会话的步骤出口（UserFeedbackNode 收口时调用） */
    public static void unbind(String sessionId) {
        if (sessionId != null) {
            SINKS.remove(sessionId);
        }
    }

    /** 取会话的步骤出口；未绑定返回 null */
    public static ReActStepSink of(String sessionId) {
        return sessionId == null ? null : SINKS.get(sessionId);
    }

    /**
     * 从工具上下文解析步骤出口：工具上下文带的是<b>子会话</b>的 sessionId，
     * 而子会话的步骤要发到<b>父回合</b>的出口，因此调用方（派发工具）用父会话 ID 取出口，
     * 这里仅在能拿到 sessionId 时兜底（工具被直接调用时返回 null）。
     */
    public static ReActStepSink from(ToolContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return of(ctx.sessionId());
        } catch (Exception e) {
            log.debug("步骤出口解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发一条步骤。任何异常都只记日志：观测能力不能反过来打断正在执行的回合
     * （子任务并发上报时尤其重要）。
     *
     * @param step 步骤（形状见 {@link StepEvents}）
     */
    public void emit(Map<String, Object> step) {
        if (step == null || step.isEmpty()) {
            return;
        }
        try {
            consumer.accept(step);
        } catch (Exception e) {
            log.debug("步骤上报失败 sessionId={} type={}: {}", sessionId, step.get(StepEvents.F_TYPE), e.getMessage());
        }
    }
}

package jainingfei.site.ai.agent.cases.react;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import jianingfei.site.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 节点抽象基类（V3 回合流水线）。
 *
 * <p>封装所有节点的通用能力：
 * <ul>
 *   <li>Bean 路由（通过 ApplicationContext）</li>
 *   <li>SSE 事件发射（兼容现有前端协议 {@code {"v":"...","s":{...}}}）</li>
 *   <li>心跳保活（15 秒间隔的 SSE 注释行）</li>
 *   <li>终端会话绑定（会话级映射 + ThreadLocal 双写）</li>
 * </ul>
 *
 * <p>继承 wrench 框架的 {@link AbstractMultiThreadStrategyRouter}，
 * 与域模块的 {@code AbstractArmorySupport} 使用同一套路由模式。
 *
 * <p>节点链路（无循环回跳，循环由 ADK Runner 托管）：
 * RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport
        extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    /** 会话 → 终端会话 ID 映射（跨节点共享） */
    protected static final Map<String, String> sessionTerminalMapping = new ConcurrentHashMap<>();

    /** 当前线程绑定的终端会话 ID（InheritableThreadLocal 支持异步线程继承） */
    protected static final InheritableThreadLocal<String> currentTerminalSession = new InheritableThreadLocal<>();

    @Override
    protected void multiThread(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException {
        // 暂无异步预加载需求
    }

    // ═══════════════════════════════════════════════════════════════
    //  Bean 路由
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    // ═══════════════════════════════════════════════════════════════
    //  终端会话绑定（ThreadLocal + 会话级映射）
    // ═══════════════════════════════════════════════════════════════

    protected static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
    }

    protected static String getCurrentTerminalSession() {
        return currentTerminalSession.get();
    }

    protected static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
    }

    protected static void bindTerminalSession(String sessionId, String terminalSessionId) {
        if (sessionId != null && terminalSessionId != null) {
            sessionTerminalMapping.put(sessionId, terminalSessionId);
        }
    }

    protected static String getTerminalSession(String sessionId) {
        return sessionId != null ? sessionTerminalMapping.get(sessionId) : null;
    }

    protected static void unbindTerminalSession(String sessionId) {
        if (sessionId != null) {
            sessionTerminalMapping.remove(sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射（信封与步骤形状见 SseSteps / StepEvents）
    // ═══════════════════════════════════════════════════════════════

    /** 发送文本事件，格式：{@code {"v":"text"}} */
    protected void sendText(SseEmitter emitter, String content) {
        SseSteps.sendText(emitter, content);
    }

    /** 发送纯步骤事件，格式：{@code {"v":"","s":{...}}} */
    protected void sendStep(SseEmitter emitter, Map<String, Object> step) {
        SseSteps.sendStep(emitter, step);
    }

    /**
     * 发送工具调用事件（运行中）：带调用 id 与父步骤 id，前端据此把结果配回同一步骤
     * （旧协议没有 id，前端只能按 FIFO 配对，并发时必然串台）。
     */
    protected void sendToolCallEvent(SseEmitter emitter, String id, String toolName,
                                     Map<String, Object> args, String kind, String parentId) {
        SseSteps.sendStep(emitter, StepEvents.toolCall(id, toolName, args, kind, parentId));
    }

    /** 发送工具结果事件（终态）：id 与调用一致，前端原地升级同一张卡片 */
    protected void sendToolResultEvent(SseEmitter emitter, String id, String toolName,
                                       Object response, boolean success, Long elapsedMs) {
        SseSteps.sendStep(emitter, StepEvents.toolResult(id, toolName, response, success, elapsedMs));
    }

    /**
     * 发送 done 事件，SSE 流结束信号。
     *
     * @param stopReason completed / error（前端据此区分"正常结束"与"本轮中断"）
     */
    protected void sendDone(SseEmitter emitter, String stopReason) {
        SseSteps.sendDone(emitter, stopReason);
    }

    /** 发送 error 事件（前端必须能看到原因，而不是流突然结束） */
    protected void sendError(SseEmitter emitter, String errorMsg) {
        SseSteps.sendError(emitter, errorMsg);
    }

    /** 发送本回合 token / 缓存命中累计 */
    protected void sendUsage(SseEmitter emitter, long promptTokens, long completionTokens,
                             long totalTokens, long cachedTokens) {
        SseSteps.sendUsage(emitter, promptTokens, completionTokens, totalTokens, cachedTokens);
    }

    // ═══════════════════════════════════════════════════════════════
    //  心跳保活
    // ═══════════════════════════════════════════════════════════════

    /** 启动 SSE 心跳线程（每 15 秒发注释行，防止代理超时断开） */
    protected static Thread startHeartbeat(SseEmitter emitter, String label) {
        AtomicBoolean streaming = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            try {
                while (streaming.get()) {
                    Thread.sleep(15000);
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (Exception e) {
                        // 心跳发不出去 = 客户端大概率已断开：标记取消，AiCallNode 据此 dispose
                        // 本轮 Runner 事件流（否则客户端已经走了，后台还在白烧模型与工具调用）
                        SseSteps.cancel(emitter, "心跳发送失败（客户端可能已断开）: " + e.getMessage());
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "sse-heartbeat-" + label);
        t.setDaemon(true);
        t.start();

        // 注册回调：流结束或出错时停止心跳；断开/超时同时作为"取消本回合"的入口
        emitter.onCompletion(() -> {
            streaming.set(false);
            SseSteps.release(emitter);
        });
        emitter.onTimeout(() -> {
            streaming.set(false);
            SseSteps.cancel(emitter, "SSE 连接超时（客户端可能已断开）");
        });
        emitter.onError(e -> {
            streaming.set(false);
            SseSteps.cancel(emitter, "SSE 连接错误（客户端可能已断开）: "
                    + (e == null ? "unknown" : e.getMessage()));
        });

        return t;
    }

}

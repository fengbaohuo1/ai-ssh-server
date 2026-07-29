package jainingfei.site.ai.agent.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActEventDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jianingfei.site.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 节点抽象基类。
 *
 * <p>封装所有节点的通用能力：
 * <ul>
 *   <li>Bean 路由（通过 ApplicationContext）</li>
 *   <li>SSE 事件发射（兼容现有前端协议 {@code {"v":"...","s":{...}}}）</li>
 *   <li>心跳保活（15 秒间隔的 SSE 注释行）</li>
 *   <li>终端会话 ThreadLocal 绑定</li>
 * </ul>
 *
 * <p>继承 wrench 框架的 {@link AbstractMultiThreadStrategyRouter}，
 * 与域模块的 {@code AbstractArmorySupport} 使用同一套路由模式。
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport
        extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected static final ObjectMapper MAPPER = new ObjectMapper();

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
    //  SSE 事件发射（兼容现有前端协议 {"v":"...","s":{...}}）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送文本事件，格式：{@code {"v":"text","s":...}} 或 {@code {"v":"text"}}
     */
    protected void sendText(SseEmitter emitter, String content, String fullText, Map<String, Object> step) {
        try {
            StringBuilder data = new StringBuilder();
            data.append("{\"v\":").append(MAPPER.writeValueAsString(content));
            if (step != null && !step.isEmpty()) {
                data.append(",\"s\":[").append(MAPPER.writeValueAsString(step)).append("]");
            }
            data.append("}");
            emitter.send(SseEmitter.event().data(data.toString()));
            log.debug("SSE text: contentLen={} hasStep={}", content.length(), step != null);
        } catch (Exception e) {
            log.warn("SSE text 发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送纯步骤事件（无文本），格式：{@code {"v":"","s":{...}}}
     */
    protected void sendStep(SseEmitter emitter, Map<String, Object> step) {
        try {
            String data = "{\"v\":\"\",\"s\":" + MAPPER.writeValueAsString(step) + "}";
            emitter.send(SseEmitter.event().data(data));
            log.debug("SSE step: {}", step.get("t"));
        } catch (Exception e) {
            log.warn("SSE step 发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具调用事件（将 ADK functionCall 转为前端 step 格式）。
     */
    protected void sendToolCallEvent(SseEmitter emitter, String toolName, Map<String, Object> args) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("t", "tool_call");
        step.put("n", toolName);
        if (args != null) step.put("a", args);
        sendStep(emitter, step);
    }

    /**
     * 发送工具结果事件（将 ADK functionResponse 转为前端 step 格式）。
     */
    protected void sendToolResultEvent(SseEmitter emitter, String toolName, Object response) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("t", "tool_result");
        step.put("n", toolName);
        if (response != null) step.put("r", response);
        sendStep(emitter, step);
    }

    /**
     * 发送 done 事件，SSE 流结束信号。
     */
    protected void sendDone(SseEmitter emitter, String finalText) {
        try {
            // 只发 done 标记，不带全文（避免前端重复渲染）
            String json = "{\"v\":\"\",\"s\":{\"t\":\"done\"}}";
            emitter.send(SseEmitter.event().data(json));
            log.info("SSE done 已发送");
        } catch (Exception e) {
            log.warn("SSE done 发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送 error 事件。
     */
    protected void sendError(SseEmitter emitter, String errorMsg) {
        try {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("t", "error");
            step.put("m", errorMsg);
            String data = "{\"v\":\"\",\"s\":" + MAPPER.writeValueAsString(step) + "}";
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception ignored) {
        }
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
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "sse-heartbeat-" + label);
        t.setDaemon(true);
        t.start();

        // 注册回调：流结束或出错时停止心跳
        emitter.onCompletion(() -> streaming.set(false));
        emitter.onTimeout(() -> streaming.set(false));
        emitter.onError(e -> streaming.set(false));

        return t;
    }

}

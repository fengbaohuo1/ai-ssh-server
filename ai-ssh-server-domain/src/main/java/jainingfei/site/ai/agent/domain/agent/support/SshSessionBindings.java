package jainingfei.site.ai.agent.domain.agent.support;

import com.google.adk.tools.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端会话绑定——"这次对话该在哪条 SSH 连接上执行命令"的唯一出处。
 *
 * <p>两级来源（解析顺序固定）：
 * <pre>
 *   ① 会话 state（键 {@link #STATE_KEY_TERMINAL_SESSION}）
 *      子智能体派发链路用它：派发时把父会话绑定的 connectionId 写进<b>子会话初始 state</b>，
 *      随会话走，与命令在哪个线程执行无关（教程用 ThreadLocal，在 ADK 的 io 线程/线程池下不可靠）
 *   ② 静态绑定表（键 = ADK 会话 ID）
 *      主链路用它：ChatService / RootNode 在 runAsync 前 bindSession
 * </pre>
 *
 * <p>为什么独立成类：这不是"工具的能力"，而是跨层协议——工具、派发上下文、派发服务、
 * 案例层节点（绑定/解绑）都要用它；挂在某个工具类上会让调用方为了拿绑定关系而依赖工具。
 */
@Slf4j
public final class SshSessionBindings {

    /** 会话 state 中承载 SSH 终端会话 ID 的键（跨会话协议，不可随意改名） */
    public static final String STATE_KEY_TERMINAL_SESSION = "terminalSessionId";

    /** sessionId → connectionId（静态，多会话安全） */
    private static final ConcurrentHashMap<String, String> SESSION_CONNECTIONS = new ConcurrentHashMap<>();

    private SshSessionBindings() {
    }

    /** 绑定会话到 SSH 连接（主链路在 runAsync 之前调用） */
    public static void bindSession(String sessionId, String connectionId) {
        SESSION_CONNECTIONS.put(sessionId, connectionId);
        log.debug("绑定会话: sessionId={} connectionId={}", sessionId, connectionId);
    }

    /** 解绑会话 */
    public static void unbindSession(String sessionId) {
        SESSION_CONNECTIONS.remove(sessionId);
    }

    /** 查询会话绑定的 SSH 连接 ID（只读）；未绑定返回 null */
    public static String connectionOf(String sessionId) {
        return sessionId == null ? null : SESSION_CONNECTIONS.get(sessionId);
    }

    /**
     * 从工具上下文解析"当前应在哪条 SSH 连接上执行"：会话 state 优先，静态绑定表兜底。
     *
     * @param ctx 工具上下文（可为 null，如工具被直接调用）
     * @return connectionId；解析不到返回 null
     */
    public static String resolveTerminalSession(ToolContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Object fromState = ctx.state().get(STATE_KEY_TERMINAL_SESSION);
            if (fromState instanceof String stateValue && !stateValue.isBlank()) {
                return stateValue;
            }
        } catch (Exception e) {
            log.debug("会话 state 读取失败，回退静态绑定表: {}", e.getMessage());
        }
        return connectionOf(ctx.sessionId());
    }
}

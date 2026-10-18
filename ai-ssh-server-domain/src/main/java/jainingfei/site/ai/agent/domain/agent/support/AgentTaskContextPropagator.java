package jainingfei.site.ai.agent.domain.agent.support;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.security.support.AuditContext;

/**
 * 子智能体任务上下文传播器——把"父线程的审计上下文 + 当前执行作用域"带到线程池/子智能体线程。
 *
 * <p><b>解决两个具体问题</b>：
 * <ol>
 *   <li>{@link AuditContext} 是 ThreadLocal，跨 {@code threadPoolExecutor} 提交后丢失，
 *       子智能体的 SSH 工具写审计时 userId 退化成 {@code default}、连接上下文丢失。
 *       这里在<b>提交边界</b>用 {@link AuditContext#wrap(Runnable)} 传播快照，并在子智能体执行期
 *       用 {@link AgentExecutionContext} 的 userId / terminalSessionId 覆盖补齐（父会话才是权威来源）。</li>
 *   <li>命令熔断计数需要"会话/智能体维度"才能隔离不同子智能体的空命令调用（否则同一条 SSH 连接上
 *       多个子智能体互相串扰）。{@link #currentScope()} 由 {@link #bind} 写入，{@code CommandGuard}
 *       读取后拼进计数 key；取不到时调用方回退到原有 connectionId 维度。</li>
 * </ol>
 *
 * <p><b>设计约束</b>：不使用 static 全局可变上下文（会把并发请求串在一起），
 * 只用"父线程快照 + 任务体恢复"的包装方式；恢复时还原成工作线程原值，绝不污染池线程。
 */
public final class AgentTaskContextPropagator {

    /**
     * 当前执行作用域（会话/智能体维度）。
     * <p>格式 {@code userId@appName/agentName}，用于命令熔断计数隔离；未包装时为 null。
     */
    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private AgentTaskContextPropagator() {
    }

    /**
     * 绑定本次子智能体执行的上下文（审计快照 + 作用域），返回值必须 close（try-with-resources）。
     *
     * <p>之所以在派发执行器里 bind 而不是完全依赖父线程快照：单任务派发工具是同步调用，
     * 父上下文可能来自并非 Web 过滤器的 ADK 调度线程，用 {@link AgentExecutionContext} 里的
     * userId / terminalSessionId 兜底更可靠。
     */
    public static Scope bind(AgentExecutionContext context, String agentName) {
        AuditContext.Holder previous = AuditContext.capture();
        String previousScope = SCOPE.get();
        AuditContext.restore(overlay(previous, context));
        String scope = scopeOf(context, agentName);
        if (scope == null) {
            SCOPE.remove();
        } else {
            SCOPE.set(scope);
        }
        return () -> {
            AuditContext.restore(previous);
            if (previousScope == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previousScope);
            }
        };
    }

    /** 当前会话/智能体维度作用域（未绑定返回 null） */
    public static String currentScope() {
        return SCOPE.get();
    }

    /**
     * 包装一个提交到线程池的任务：执行期携带父线程审计快照 + 本次派发的 userId / connectionId / 作用域。
     *
     * @param task      待提交任务
     * @param context   派发上下文（可为 null，此时等价于 {@link AuditContext#wrap(Runnable)}）
     * @param agentName 子智能体名（参与作用域隔离，可为 null）
     */
    public static Runnable wrap(Runnable task, AgentExecutionContext context, String agentName) {
        if (task == null) {
            return null;
        }
        AuditContext.Holder merged = overlay(AuditContext.capture(), context);
        String scope = scopeOf(context, agentName);
        return () -> {
            AuditContext.Holder previous = AuditContext.capture();
            String previousScope = SCOPE.get();
            AuditContext.restore(merged);
            if (scope == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(scope);
            }
            try {
                task.run();
            } finally {
                AuditContext.restore(previous);
                if (previousScope == null) {
                    SCOPE.remove();
                } else {
                    SCOPE.set(previousScope);
                }
            }
        };
    }

    /**
     * 捕获<b>调用方线程</b>的上下文快照并包装任务：任务执行线程上恢复快照，结束后还原该线程原值。
     *
     * <p>与 {@link #wrap(Runnable, AgentExecutionContext, String)} 的区别：那个是"提交前已知派发上下文"，
     * 这个是"提交时刻现场取父线程上下文"，用于 Rx 调度器这类我们把任务交给别人的边界
     * （例如 {@code Schedulers.from(executor)} 的自定义线程池：执行器在调用线程上收到任务，
     * 此时父线程的 ThreadLocal 还在，正好可以快照下来）。
     */
    public static Runnable captureCurrent(Runnable task) {
        if (task == null) {
            return null;
        }
        AuditContext.Holder captured = AuditContext.capture();
        String capturedScope = SCOPE.get();
        return () -> {
            AuditContext.Holder previous = AuditContext.capture();
            String previousScope = SCOPE.get();
            AuditContext.restore(captured);
            if (capturedScope == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(capturedScope);
            }
            try {
                task.run();
            } finally {
                AuditContext.restore(previous);
                if (previousScope == null) {
                    SCOPE.remove();
                } else {
                    SCOPE.set(previousScope);
                }
            }
        };
    }

    /** 父快照 + 派发上下文合并：派发上下文有值优先（父会话是权威来源），其余保留父线程值 */
    private static AuditContext.Holder overlay(AuditContext.Holder parent, AgentExecutionContext context) {
        if (context == null) {
            return parent;
        }
        String userId = notBlank(context.getUserId())
                ? context.getUserId()
                : (parent == null ? null : parent.userId());
        String clientIp = parent == null ? null : parent.clientIp();
        String connectionId = notBlank(context.getTerminalSessionId())
                ? context.getTerminalSessionId()
                : (parent == null ? null : parent.connectionId());
        return new AuditContext.Holder(userId, clientIp, connectionId);
    }

    /** 作用域标识：userId@appName/agentName（可空字段跳过；全空返回 null） */
    private static String scopeOf(AgentExecutionContext context, String agentName) {
        if (context == null && !notBlank(agentName)) {
            return null;
        }
        StringBuilder scope = new StringBuilder();
        if (context != null && notBlank(context.getUserId())) {
            scope.append(context.getUserId()).append('@');
        }
        if (context != null && notBlank(context.getAppName())) {
            scope.append(context.getAppName());
        }
        if (notBlank(agentName)) {
            scope.append('/').append(agentName);
        }
        return scope.length() == 0 ? null : scope.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 绑定作用域的生命周期（AutoCloseable：必须 close 以还原父线程值） */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

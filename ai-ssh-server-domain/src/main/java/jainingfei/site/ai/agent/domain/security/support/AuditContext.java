package jainingfei.site.ai.agent.domain.security.support;

import java.util.concurrent.Callable;

/**
 * 审计上下文——把"谁在操作、从哪来"从 Web 层传到领域服务，避免审计参数污染每个方法签名。
 *
 * <p>由触发层的 {@code AuditContextFilter} 在请求进入时设置、结束时清理；
 * 非 Web 线程（AI 工具 / 定时任务）取不到值时回退为 {@code default} / {@code local}。
 *
 * <p><b>线程池传播</b>：ThreadLocal 不会跨线程池提交自动传递，子智能体跑在
 * {@code threadPoolExecutor} 上时会退化成 {@code default} / {@code local}。这里提供
 * {@link #capture()} / {@link #restore(Holder)} 与 {@link #wrap(Runnable)} / {@link #wrap(Callable)}
 * 做<b>显式传播</b>（不是把 ThreadLocal 改成全局静态变量）：提交前 capture 父线程快照，
 * 任务体开头 restore、结束 restore 回工作线程原值，避免污染池线程。
 *
 * <p>连接维度（{@code connectionId}）随快照一起传播：子智能体的 SSH 工具执行在独立会话/线程上，
 * 审计兜底时仍能落到父会话绑定的那条连接。
 */
public final class AuditContext {

    private static final ThreadLocal<Holder> CONTEXT = new ThreadLocal<>();

    private AuditContext() {
    }

    /**
     * 上下文快照。
     *
     * @param userId       用户（缺省 default）
     * @param clientIp     客户端 IP（缺省 local）
     * @param connectionId SSH 连接 ID（可空；不属于 Web 请求的默认值）
     */
    public record Holder(String userId, String clientIp, String connectionId) {

        public Holder {
            userId = userId == null || userId.isBlank() ? "default" : userId;
            clientIp = clientIp == null || clientIp.isBlank() ? "local" : clientIp;
            connectionId = connectionId == null || connectionId.isBlank() ? null : connectionId;
        }
    }

    public static void set(String userId, String clientIp) {
        CONTEXT.set(new Holder(userId, clientIp, null));
    }

    /** 设置完整快照（连接维度用于子智能体/工具线程的审计兜底） */
    public static void set(String userId, String clientIp, String connectionId) {
        CONTEXT.set(new Holder(userId, clientIp, connectionId));
    }

    public static String userId() {
        Holder holder = CONTEXT.get();
        return holder == null ? "default" : holder.userId();
    }

    public static String clientIp() {
        Holder holder = CONTEXT.get();
        return holder == null ? "local" : holder.clientIp();
    }

    /** 当前线程绑定的 SSH 连接 ID（未绑定返回 null） */
    public static String connectionId() {
        Holder holder = CONTEXT.get();
        return holder == null ? null : holder.connectionId();
    }

    /** 抓取当前线程的上下文快照（未绑定返回 null，restore(null) 等价 clear） */
    public static Holder capture() {
        return CONTEXT.get();
    }

    /** 还原快照（null = 清空）：任务体结束时必须还原回工作线程原值，避免池线程串味 */
    public static void restore(Holder holder) {
        if (holder == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(holder);
        }
    }

    /**
     * 包装 Runnable：执行期携带调用线程（提交方）的上下文快照，结束还原工作线程原值。
     * <p>用于 {@code Executor.execute} / {@code CompletableFuture.runAsync(..., executor)} 等线程池边界。
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }
        Holder captured = CONTEXT.get();
        return () -> {
            Holder previous = CONTEXT.get();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装 Callable：同 {@link #wrap(Runnable)}，用于 {@code ExecutorService.submit} /
     * {@code CompletableFuture.supplyAsync(..., executor)}。
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        if (task == null) {
            return null;
        }
        Holder captured = CONTEXT.get();
        return () -> {
            Holder previous = CONTEXT.get();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

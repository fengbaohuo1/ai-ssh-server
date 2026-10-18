package jainingfei.site.ai.agent.domain.security.support;

/**
 * 审计上下文——把"谁在操作、从哪来"从 Web 层传到领域服务，避免审计参数污染每个方法签名。
 *
 * <p>由触发层的 {@code AuditContextFilter} 在请求进入时设置、结束时清理；
 * 非 Web 线程（AI 工具 / 定时任务）取不到值时回退为 {@code default} / {@code local}。
 */
public final class AuditContext {

    private static final ThreadLocal<Holder> CONTEXT = new ThreadLocal<>();

    private AuditContext() {
    }

    public record Holder(String userId, String clientIp) {
    }

    public static void set(String userId, String clientIp) {
        CONTEXT.set(new Holder(
                userId == null || userId.isBlank() ? "default" : userId,
                clientIp == null || clientIp.isBlank() ? "local" : clientIp));
    }

    public static String userId() {
        Holder holder = CONTEXT.get();
        return holder == null ? "default" : holder.userId();
    }

    public static String clientIp() {
        Holder holder = CONTEXT.get();
        return holder == null ? "local" : holder.clientIp();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

package jainingfei.site.ai.agent.cases.react;

/**
 * 回合取消异常（客户端断开 / SSE 超时 / 发送失败导致的本回合取消）。
 *
 * <p>与普通异常区分：取消不是失败——任务态保持可续接（LoopDecisionNode 按中断处理），
 * 结果 stopReason 记为 {@code cancelled}，也不会再向已断开的前端发 error 事件。
 */
public class TurnCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TurnCancelledException(String message) {
        super(message);
    }
}

package jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic;

/**
 * 动态派发任务的状态机（教程 2-10）。
 *
 * <pre>
 *   PENDING ──► RUNNING ──► COMPLETED
 *      │             │
 *      │             └─────► FAILED（本任务执行失败，含重试耗尽）
 *      │
 *      └──► SKIPPED（上游依赖 FAILED/SKIPPED，或计划 failFast 中止）
 * </pre>
 *
 * <p>为什么要区分 FAILED 与 SKIPPED：对父智能体来说
 * <b>FAILED = "试过了没成功"</b>（可以换命令重试），
 * <b>SKIPPED = "因为上游失败根本没执行"</b>（需要先解决上游）——
 * 两种终态给模型的下一步决策完全不同的信息。
 */
public enum TaskStatus {

    /** 等待调度 */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 执行成功 */
    COMPLETED,

    /** 执行失败（含重试耗尽） */
    FAILED,

    /** 被跳过（上游失败级联，或 failFast 中止） */
    SKIPPED

}

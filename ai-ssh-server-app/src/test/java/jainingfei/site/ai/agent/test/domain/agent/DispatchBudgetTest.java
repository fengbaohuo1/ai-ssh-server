package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.support.DispatchBudget;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派发预算（DispatchBudget）单元测试——纯静态账本逻辑。
 *
 * <p>覆盖：派发调用次数上限、子任务总量上限、单个子智能体上限、同一轮重复任务去重、
 * 子智能体嵌套派发拦截、beginTurn 重置、空 sessionId 退化为不限制（不抛异常）。
 */
public class DispatchBudgetTest {

    /** 每个用例独立 session，避免静态账本串味 */
    private String newSession() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    public void test_dispatch_call_limit() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        for (int i = 0; i < DispatchBudget.MAX_DISPATCH_CALLS_PER_TURN; i++) {
            assertNull(DispatchBudget.reserveDispatchCall(session), "第 " + (i + 1) + " 次派发应放行");
        }
        String rejected = DispatchBudget.reserveDispatchCall(session);
        assertNotNull(rejected, "超过派发次数上限应被拦截");
        assertTrue(rejected.contains("上限"), "拦截文案应说明上限：" + rejected);
    }

    @Test
    public void test_child_task_total_limit() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        // 交替使用两个子智能体，绕开"同智能体上限 4"先触顶，验证的是子任务总量上限 6
        String[] agents = {"sshDiagnosisAgent", "sshChangeAgent"};
        int accepted = 0;
        for (int i = 0; i < DispatchBudget.MAX_CHILD_TASKS_PER_TURN + 2; i++) {
            String error = DispatchBudget.reserveTask(session, agents[i % agents.length], "检查第 " + i + " 项");
            if (error == null) {
                accepted++;
            }
        }
        assertEquals(DispatchBudget.MAX_CHILD_TASKS_PER_TURN, accepted, "子任务总量应受上限约束");
    }

    @Test
    public void test_per_agent_limit() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        // 换 agent 名绕开"总量上限"的干扰：单个 agent 的任务数上限更小，应先生效
        int accepted = 0;
        for (int i = 0; i < DispatchBudget.MAX_TASKS_PER_AGENT_PER_TURN + 2; i++) {
            if (DispatchBudget.reserveTask(session, "sshChangeAgent", "变更第 " + i + " 项") == null) {
                accepted++;
            }
        }
        assertEquals(DispatchBudget.MAX_TASKS_PER_AGENT_PER_TURN, accepted, "同一子智能体的任务数应受上限约束");
    }

    @Test
    public void test_duplicate_task_rejected() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        assertNull(DispatchBudget.reserveTask(session, "sshDiagnosisAgent", "检查磁盘占用"), "首次派发放行");
        String duplicate = DispatchBudget.reserveTask(session, "sshDiagnosisAgent", "  检查磁盘占用  ");
        assertNotNull(duplicate, "归一化后相同的任务应被去重拦截");
        assertTrue(duplicate.contains("重复派发"), "去重文案应提示重复：" + duplicate);
        // 不同智能体 + 相同描述是合法的（诊断与变更同一目标）
        assertNull(DispatchBudget.reserveTask(session, "sshChangeAgent", "检查磁盘占用"));
    }

    @Test
    public void test_begin_turn_resets_budget() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        for (int i = 0; i < DispatchBudget.MAX_DISPATCH_CALLS_PER_TURN; i++) {
            DispatchBudget.reserveDispatchCall(session);
        }
        assertNotNull(DispatchBudget.reserveDispatchCall(session), "上一轮额度应已用尽");
        DispatchBudget.beginTurn(session);
        assertNull(DispatchBudget.reserveDispatchCall(session), "新一轮应重新获得额度");
    }

    @Test
    public void test_child_session_dispatch_rejected() {
        String child = DispatchBudget.CHILD_SESSION_PREFIX + UUID.randomUUID();
        assertTrue(DispatchBudget.isChildSession(child));
        assertNotNull(DispatchBudget.rejectChildSessionDispatch(child), "子智能体再派发应被拒绝");
        assertNull(DispatchBudget.rejectChildSessionDispatch("session-" + UUID.randomUUID()), "普通会话应放行");
        assertNull(DispatchBudget.rejectChildSessionDispatch(null), "空 sessionId 不拦截");
    }

    @Test
    public void test_blank_session_is_no_op() {
        assertNull(DispatchBudget.reserveDispatchCall(null));
        assertNull(DispatchBudget.reserveDispatchCall("  "));
        assertNull(DispatchBudget.reserveTask(null, "sshDiagnosisAgent", "任务"));
    }

    @Test
    public void test_clear_releases_session() {
        String session = newSession();
        DispatchBudget.beginTurn(session);
        DispatchBudget.clear(session);
        // 清理后按新账本计：仍可正常放行一次
        assertNull(DispatchBudget.reserveDispatchCall(session));
    }
}

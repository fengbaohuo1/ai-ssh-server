package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.service.intent.ContextTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务步进状态机单元测试（无网络、无 Spring，直接驱动 ContextTracker）。
 * <p>
 * 覆盖 V3.2 回合语义的三入口写路径：
 * <pre>
 *   syncTaskStateAfterClassification（AiCallNode 回合入口）
 *     ├─ CONTINUE + 未完成任务 → 续接（清失败标记、校准索引）
 *     ├─ UNKNOWN / CHAT        → 跳过（不建不毁）
 *     ├─ 业务意图 无任务/已完成/意图变更 → 重建
 *     └─ 业务意图 同根意图未完成 → 复用并刷新
 *   updateTaskStateAfterFeedback（ToolCallNode 每工具结果）
 *     ├─ 成功 → 追加动作留痕 + 清失败标记（不置 completed）
 *     ├─ 失败 → 置失败标记
 *     └─ 重分类业务意图 → 替换 rootIntent
 *   markTaskCompleted / markTaskInterrupted（LoopDecisionNode 回合收口）
 * </pre>
 */
public class TaskStateMachineTest {

    private final ContextTracker tracker = new ContextTracker();

    private static final String SID = "session-1";

    private IntentResultVO intent(IntentTypeEnumVO type) {
        return IntentResultVO.builder()
                .intent(type)
                .confidence(0.9)
                .candidateIntents(java.util.List.of())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  sync：创建 / 重建 / 复用
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void test_business_intent_creates_new_task() {
        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "帮我查下磁盘占用", intent(IntentTypeEnumVO.MONITOR));
        assertNotNull(state);
        assertEquals("帮我查下磁盘占用", state.getTaskDescription());
        assertEquals(IntentTypeEnumVO.MONITOR, state.getRootIntent());
        assertEquals(java.util.List.of("帮我查下磁盘占用"), state.getSteps());
        assertEquals(0, state.getCurrentStepIndex());
        assertFalse(state.isCompleted());
        assertFalse(state.isLastFeedbackFailed());
    }

    @Test
    public void test_same_root_intent_reuses_task_and_keeps_anchor() {
        tracker.syncTaskStateAfterClassification(SID, "帮我查下磁盘占用", intent(IntentTypeEnumVO.MONITOR));
        // 同意图延续消息（补充指令）：复用任务，锚点不覆盖
        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "再看看 /tmp 目录", intent(IntentTypeEnumVO.MONITOR));
        assertEquals("帮我查下磁盘占用", state.getTaskDescription(), "复用不得覆盖原始任务锚点");
        assertEquals(1, state.getSteps().size(), "复用不追加新步骤（步骤留痕只由成功工具动作增长）");
    }

    @Test
    public void test_different_business_intent_replaces_task() {
        tracker.syncTaskStateAfterClassification(SID, "帮我查下磁盘占用", intent(IntentTypeEnumVO.MONITOR));
        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "帮我改下 nginx 配置", intent(IntentTypeEnumVO.CONFIGURE));
        assertEquals(IntentTypeEnumVO.CONFIGURE, state.getRootIntent());
        assertEquals("帮我改下 nginx 配置", state.getTaskDescription());
    }

    @Test
    public void test_completed_task_recreated_on_same_intent() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.markTaskCompleted(SID);
        // 已完成 + 同意图 → 重建（新任务新锚点），而不是复用旧任务
        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "再查下网关 502", intent(IntentTypeEnumVO.DIAGNOSE));
        assertFalse(state.isCompleted());
        assertEquals("再查下网关 502", state.getTaskDescription());
    }

    @Test
    public void test_unknown_and_chat_skip_task_state() {
        assertNull(tracker.syncTaskStateAfterClassification(SID, "你好", intent(IntentTypeEnumVO.CHAT)));
        assertNull(tracker.getTaskState(SID));

        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        // 闲聊不冲掉进行中任务
        TaskStateVO afterChat = tracker.syncTaskStateAfterClassification(SID, "谢谢", intent(IntentTypeEnumVO.CHAT));
        assertEquals(IntentTypeEnumVO.DIAGNOSE, tracker.getTaskState(SID).getRootIntent());
        assertEquals(IntentTypeEnumVO.DIAGNOSE, afterChat.getRootIntent());
        // UNKNOWN 同样不动
        TaskStateVO afterUnknown = tracker.syncTaskStateAfterClassification(SID, "？？？", intent(IntentTypeEnumVO.UNKNOWN));
        assertEquals(IntentTypeEnumVO.DIAGNOSE, afterUnknown.getRootIntent());
    }

    // ═══════════════════════════════════════════════════════════════
    //  sync：CONTINUE 续接
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void test_continue_without_task_does_nothing() {
        assertNull(tracker.syncTaskStateAfterClassification(SID, "继续", intent(IntentTypeEnumVO.CONTINUE)));
        assertNull(tracker.getTaskState(SID));
    }

    @Test
    public void test_continue_resumes_active_task_and_clears_failure() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        // 制造一次失败
        tracker.updateTaskStateAfterFeedback(SID, false, null, "tail error.log");
        assertTrue(tracker.getTaskState(SID).isLastFeedbackFailed());

        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "继续", intent(IntentTypeEnumVO.CONTINUE));
        assertNotNull(state);
        assertFalse(state.isLastFeedbackFailed(), "CONTINUE 续接应清除失败标记");
        assertEquals(IntentTypeEnumVO.DIAGNOSE, state.getRootIntent());
    }

    @Test
    public void test_continue_on_completed_task_keeps_completed() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.markTaskCompleted(SID);
        TaskStateVO state = tracker.syncTaskStateAfterClassification(SID, "继续", intent(IntentTypeEnumVO.CONTINUE));
        assertTrue(state.isCompleted(), "已完成任务不因 CONTINUE 复活");
    }

    // ═══════════════════════════════════════════════════════════════
    //  update：工具反馈打点
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void test_feedback_without_task_is_noop() {
        assertNull(tracker.updateTaskStateAfterFeedback(SID, true, null, "df -h"));
    }

    @Test
    public void test_success_appends_trace_but_not_completed() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        TaskStateVO state = tracker.updateTaskStateAfterFeedback(SID, true, null, "tail error.log");
        assertEquals(java.util.List.of("查下 502", "tail error.log"), state.getSteps());
        assertEquals(1, state.getCurrentStepIndex(), "currentStepIndex = steps.size()-1");
        assertFalse(state.isLastFeedbackFailed());
        // V3.2 关键差异：单次成功不得立即归档（托管循环一个回合多工具，完成由收口判定）
        assertFalse(state.isCompleted());
    }

    @Test
    public void test_failure_sets_flag_success_clears() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        assertTrue(tracker.updateTaskStateAfterFeedback(SID, false, null, "tail error.log").isLastFeedbackFailed());
        TaskStateVO state = tracker.updateTaskStateAfterFeedback(SID, true, null, "tail error2.log");
        assertFalse(state.isLastFeedbackFailed(), "后续成功应清除失败标记");
        // 失败动作不入留痕
        assertEquals(java.util.List.of("查下 502", "tail error2.log"), state.getSteps());
    }

    @Test
    public void test_reclassified_business_intent_replaces_root_intent() {
        tracker.syncTaskStateAfterClassification(SID, "改下 nginx 配置", intent(IntentTypeEnumVO.CONFIGURE));
        IntentResultVO reclassified = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.MONITOR)
                .confidence(0.6)
                .reclassified(true)
                .candidateIntents(java.util.List.of())
                .build();
        TaskStateVO state = tracker.updateTaskStateAfterFeedback(SID, false, reclassified, "cat nginx.conf");
        assertEquals(IntentTypeEnumVO.MONITOR, state.getRootIntent(), "重分类业务意图应替换根意图");
    }

    @Test
    public void test_reclassified_fallback_intent_keeps_root_intent() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        IntentResultVO fallback = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .reclassified(true)
                .build();
        TaskStateVO state = tracker.updateTaskStateAfterFeedback(SID, false, fallback, "xx");
        assertEquals(IntentTypeEnumVO.DIAGNOSE, state.getRootIntent(), "兜底意图不替换根意图");
    }

    @Test
    public void test_steps_cap_rolls_oldest() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        for (int i = 1; i <= 15; i++) {
            tracker.updateTaskStateAfterFeedback(SID, true, null, "cmd-" + i);
        }
        TaskStateVO state = tracker.getTaskState(SID);
        assertEquals(12, state.getSteps().size(), "留痕上限 12 条滚动");
        assertEquals("cmd-4", state.getSteps().get(0), "超出后丢最旧（首条用户消息也在滚动范围内）");
        assertEquals(11, state.getCurrentStepIndex());
    }

    // ═══════════════════════════════════════════════════════════════
    //  收口：markTaskCompleted / markTaskInterrupted
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void test_mark_completed_archives_task() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.updateTaskStateAfterFeedback(SID, true, null, "tail error.log");
        tracker.markTaskCompleted(SID);
        TaskStateVO state = tracker.getTaskState(SID);
        assertTrue(state.isCompleted());
        assertFalse(state.isLastFeedbackFailed());
        // 完成后同意图新消息 → 重建
        TaskStateVO fresh = tracker.syncTaskStateAfterClassification(SID, "再看看内存", intent(IntentTypeEnumVO.DIAGNOSE));
        assertEquals("再看看内存", fresh.getTaskDescription());
        assertFalse(fresh.isCompleted());
    }

    @Test
    public void test_mark_interrupted_keeps_task_resumable() {
        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.markTaskInterrupted(SID);
        TaskStateVO state = tracker.getTaskState(SID);
        assertFalse(state.isCompleted(), "中断不归档，保持可续接");
        assertTrue(state.isLastFeedbackFailed());
        // 中断后 CONTINUE → 续接成功
        TaskStateVO resumed = tracker.syncTaskStateAfterClassification(SID, "继续", intent(IntentTypeEnumVO.CONTINUE));
        assertFalse(resumed.isLastFeedbackFailed());
        assertEquals(IntentTypeEnumVO.DIAGNOSE, resumed.getRootIntent());
    }

    @Test
    public void test_mark_ops_are_noop_without_task_or_completed() {
        tracker.markTaskCompleted(SID);
        tracker.markTaskInterrupted(SID);
        assertNull(tracker.getTaskState(SID));

        tracker.syncTaskStateAfterClassification(SID, "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.markTaskCompleted(SID);
        tracker.markTaskInterrupted(SID); // 已完成任务不再打中断
        assertTrue(tracker.getTaskState(SID).isCompleted());
        assertFalse(tracker.getTaskState(SID).isLastFeedbackFailed());
    }

    // ═══════════════════════════════════════════════════════════════
    //  会话隔离
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void test_task_state_isolated_by_session() {
        tracker.syncTaskStateAfterClassification("session-A", "查下 502", intent(IntentTypeEnumVO.DIAGNOSE));
        tracker.syncTaskStateAfterClassification("session-B", "改下配置", intent(IntentTypeEnumVO.CONFIGURE));
        assertEquals(IntentTypeEnumVO.DIAGNOSE, tracker.getTaskState("session-A").getRootIntent());
        assertEquals(IntentTypeEnumVO.CONFIGURE, tracker.getTaskState("session-B").getRootIntent());
    }
}

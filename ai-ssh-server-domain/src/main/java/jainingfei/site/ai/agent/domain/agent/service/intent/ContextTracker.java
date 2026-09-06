package jainingfei.site.ai.agent.domain.agent.service.intent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.ConversationContextVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentHistoryEntryVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文追踪器
 * <p>
 * 按 sessionId 维护会话级意图历史（滑动窗口 10 条）与任务态，
 * 供规则分类器和 LLM 分类器做上下文加权，并支撑 CONTINUE / 自纠错。
 * <p>
 * 采用惰性过期清理 + 会话数上限保护，避免长期运行内存无限增长。
 *
 * <p>数据流：
 * <pre>
 *   IntentService.classify / reportFeedback
 *      │  getContext(sessionId)            ← 取（或惰性创建）上下文
 *      │  updateContext / recordFeedback   ← 回写意图历史、失败计数、任务态
 *      ▼
 *   ConversationContextVO（内存，ConcurrentHashMap）
 *      ├─ recentIntents      ← 滑动窗口 10 条，满则淘汰最旧
 *      ├─ consecutiveFailures← 反馈失败累计，≥2 触发强制兜底
 *      └─ taskState           ← 回合级任务步进状态机（V3.2）
 *      │
 *      └─ evictExpired()      ← 每次 getContext 触发：30min 无活跃淘汰，超 500 会话淘汰最旧
 * </pre>
 *
 * <p>任务态写路径（状态机三入口，均由节点链路回合语义驱动）：
 * <pre>
 *   AiCallNode（分类后）   → syncTaskStateAfterClassification   CONTINUE 续接 / 业务意图重建或复用 / CHAT·UNKNOWN 跳过
 *   ToolCallNode（每工具） → updateTaskStateAfterFeedback       成功追加留痕 / 失败置位 / 重分类换根意图
 *   LoopDecisionNode（收口）→ markTaskCompleted / markTaskInterrupted  回合正常归档 / 异常中断可续
 * </pre>
 *
 * <p>案例：会话 S1 连续 35 分钟无活动 → 下次任意会话 getContext 时被惰性清除，
 * 避免后台线程开销。
 */
@Slf4j
@Component
public class ContextTracker {

    private static final int WINDOW_SIZE = 10;
    /** 会话上下文最大存活数量上限 */
    private static final int MAX_SESSIONS = 500;
    /** 会话过期时间：30 分钟无活跃则淘汰 */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    /** 任务步骤留痕上限（滚动，超出丢最旧） */
    private static final int MAX_TASK_STEPS = 12;

    private final Map<String, ConversationContextVO> contexts = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）会话上下文。
     * <p>
     * 若会话不存在则自动创建初始上下文，并触发惰性过期清理。
     * <p>
     * 案例：
     * <pre>
     *   sessionId = "session-001"
     *
     *   第1次调用：
     *   - 会话不存在，创建新上下文
     *   - 返回初始 ConversationContextVO：
     *     { recentIntents=[], turnCount=0, sessionStartTime=xxx, lastActiveTime=xxx, consecutiveFailures=0 }
     *
     *   第2次调用（5秒后）：
     *   - 会话已存在，直接返回
     *   - 触发 evictExpired() 清理过期会话
     * </pre>
     */
    public ConversationContextVO getContext(String sessionId) {
        evictExpired();
        return contexts.computeIfAbsent(sessionId, id -> {
            long now = System.currentTimeMillis();
            return ConversationContextVO.builder()
                    .recentIntents(new LinkedList<>())
                    .turnCount(0)
                    .sessionStartTime(now)
                    .lastActiveTime(now)
                    .consecutiveFailures(0)
                    .build();
        });
    }

    /**
     * 更新会话上下文（记录意图历史、递增轮次、刷新活跃时间）
     * <p>
     * 每次分类后调用，维护滑动窗口 10 条的意图历史。
     * <p>
     * 案例：
     * <pre>
     *   初始上下文：recentIntents=[], turnCount=0
     *
     *   调用 updateContext("session-001", { intent=DIAGNOSE, conf=0.8 })
     *   -> recentIntents = [{ intent=DIAGNOSE, conf=0.8 }]
     *   -> turnCount = 1
     *
     *   调用 updateContext("session-001", { intent=MONITOR, conf=0.9 })
     *   -> recentIntents = [{ intent=DIAGNOSE }, { intent=MONITOR }]
     *   -> turnCount = 2
     *
     *   第11次调用（窗口满）：
     *   -> recentIntents = [{ intent=MONITOR }, ..., { intent=最新意图 }]
     *   -> 最旧的 intent=DIAGNOSE 被淘汰
     *   -> turnCount = 11
     * </pre>
     */
    public void updateContext(String sessionId, IntentResultVO result) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.getRecentIntents().addLast(IntentHistoryEntryVO.builder()
                .intent(result.getIntent())
                .confidence(result.getConfidence())
                .timestamp(System.currentTimeMillis())
                .build());
        if (ctx.getRecentIntents().size() > WINDOW_SIZE) {
            ctx.getRecentIntents().removeFirst();
        }
        ctx.setTurnCount(ctx.getTurnCount() + 1);
        ctx.setLastIntent(result.getIntent());
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 记录一次反馈结果（成功/失败），维护连续失败计数。
     * <p>
     * 成功时重置计数器，失败时递增。
     * <p>
     * 案例：
     * <pre>
     *   初始 consecutiveFailures = 0
     *
     *   第1次调用 recordFeedback("session-001", false)
     *   -> consecutiveFailures = 1
     *   -> 若 taskState 存在，标记 lastFeedbackFailed = true
     *
     *   第2次调用 recordFeedback("session-001", false)
     *   -> consecutiveFailures = 2
     *   -> IntentService 连续失败阈值触发：后续分类跳规则层，LLM 兜底
     *
     *   第3次调用 recordFeedback("session-001", true)
     *   -> consecutiveFailures = 0（重置）
     *   -> 恢复正常分类流程
     * </pre>
     */
    public void recordFeedback(String sessionId, boolean success) {
        ConversationContextVO ctx = getContext(sessionId);
        if (success) {
            ctx.setConsecutiveFailures(0);
        } else {
            ctx.setConsecutiveFailures(ctx.getConsecutiveFailures() + 1);
        }
        if (ctx.getTaskState() != null) {
            ctx.getTaskState().setLastFeedbackFailed(!success);
        }
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 获取会话的连续失败次数。
     * <p>
     * 供 {@link IntentService} 在 classify 前查询，连续失败 ≥2 时触发 LLM 兜底
     * （跳过规则层、放宽置信度门槛至 0.3）。
     *
     * @param sessionId 会话 ID
     * @return 连续失败次数，未初始化时返回 0
     */
    public int getConsecutiveFailures(String sessionId) {
        return getContext(sessionId).getConsecutiveFailures();
    }

    /**
     * 获取会话当前任务状态。
     * <p>
     * 任务态包含当前进行中的意图、步骤索引及完成标志，供分类器判断是否应返回
     * {@link jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO#CONTINUE}。
     *
     * @param sessionId 会话 ID
     * @return 任务状态，若会话无任务态则返回 null
     */
    public TaskStateVO getTaskState(String sessionId) {
        return getContext(sessionId).getTaskState();
    }

    /**
     * 设置或更新会话的任务状态。
     * <p>
     * 通常由主流程（如多步诊断任务启动时）调用，用于记录当前进行中的意图、
     * 步骤索引等信息，供后续 classify 和 reportFeedback 使用。
     * 调用后会同步刷新会话的 lastActiveTime，避免被惰性清理。
     *
     * @param sessionId 会话 ID
     * @param taskState 任务状态对象，可为 null（清除任务态）
     */
    public void setTaskState(String sessionId, TaskStateVO taskState) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.setTaskState(taskState);
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 分类后同步任务态（教学版 syncTaskStateAfterClassification 的回合化移植）。
     * <p>
     * 分类器拿到本条用户消息的意图后调用，按四分支维护任务态：
     * <ul>
     *   <li><b>CONTINUE</b>：存在未完成任务态 → 校准步骤索引、清除失败标记（供主模型续接）；
     *       无任务态/已完成 → 不动（交主模型从历史兜底）</li>
     *   <li><b>UNKNOWN / CHAT</b>：不维护任务态（闲聊不冲掉进行中任务）</li>
     *   <li><b>业务意图</b>：无任务态 / 已完成 / 根意图变更 → 重建新任务；否则复用并刷新
     *       （补空 description/steps、清完成与失败标记）</li>
     * </ul>
     * <p>V3 回合化差异：教学版在显式循环里每轮都同步；托管循环一次用户消息 = 一次 runAsync，
     * 本方法只在 AiCallNode 回合入口调用一次。
     *
     * @param sessionId       会话 ID
     * @param lastUserMessage 最新用户消息（作为新任务的描述/首步）
     * @param intentResult    本轮意图识别结果
     * @return 同步后的任务态（无变化也可能返回既有任务态或 null）
     */
    public TaskStateVO syncTaskStateAfterClassification(String sessionId, String lastUserMessage,
                                                        IntentResultVO intentResult) {
        if (intentResult == null || intentResult.getIntent() == null) {
            return getTaskState(sessionId);
        }
        IntentTypeEnumVO intent = intentResult.getIntent();
        TaskStateVO taskState = getTaskState(sessionId);

        // CONTINUE：有进行中任务态 → 校准索引 + 清失败标记（续接依据由主模型结合历史判断）
        if (intent == IntentTypeEnumVO.CONTINUE) {
            if (taskState != null && !taskState.isCompleted()) {
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                taskState.setLastFeedbackFailed(false);
                setTaskState(sessionId, taskState);
            }
            return taskState;
        }

        // UNKNOWN / CHAT：不维护任务态
        if (intent == IntentTypeEnumVO.UNKNOWN || intent == IntentTypeEnumVO.CHAT) {
            return taskState;
        }

        // 业务意图：重建 or 复用
        boolean shouldReplace = taskState == null
                || taskState.isCompleted()
                || taskState.getRootIntent() != intent;
        if (shouldReplace) {
            List<String> steps = new ArrayList<>();
            steps.add(lastUserMessage);
            taskState = TaskStateVO.builder()
                    .taskDescription(lastUserMessage)
                    .rootIntent(intent)
                    .steps(steps)
                    .currentStepIndex(0)
                    .completed(false)
                    .lastFeedbackFailed(false)
                    .build();
        } else {
            if (taskState.getTaskDescription() == null || taskState.getTaskDescription().isBlank()) {
                taskState.setTaskDescription(lastUserMessage);
            }
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(lastUserMessage)));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            taskState.setCompleted(false);
            taskState.setLastFeedbackFailed(false);
        }
        setTaskState(sessionId, taskState);
        return taskState;
    }

    /**
     * 反馈后更新任务态（教学版 updateTaskStateAfterFeedback 的回合化移植）。
     * <p>
     * 每个工具结果（FunctionResponse）打点一次：成功 → 追加步骤留痕并清失败标记；
     * 失败 → 置失败标记；反馈回路重分类为业务意图 → 替换根意图。
     * <p>
     * <b>V3 回合化差异：不在本方法推进 completed</b>——托管循环一个回合内模型会连续执行多个工具，
     * 若首次成功即归档任务会把"等用户确认/尚未收尾"的回合误判完成。完成/中断由
     * LoopDecisionNode 回合收口统一判定（{@link #markTaskCompleted} / {@link #markTaskInterrupted}）。
     *
     * @param sessionId     会话 ID
     * @param success       当前工具结果是否成功
     * @param reclassified  反馈回路重分类结果（可为 null）
     * @param actionSummary 成功动作的摘要（调用方已截断），追加到 steps 留痕
     * @return 更新后的任务态；无任务态返回 null，已完成归档态原样返回（不再接收打点）
     */
    public TaskStateVO updateTaskStateAfterFeedback(String sessionId, boolean success,
                                                    IntentResultVO reclassified, String actionSummary) {
        TaskStateVO taskState = getTaskState(sessionId);
        // 无任务态不打点；已完成任务为归档态（惰性等重建），不再接收留痕/标记
        if (taskState == null || taskState.isCompleted()) {
            return taskState;
        }
        if (reclassified != null && reclassified.getIntent() != null
                && reclassified.getIntent() != IntentTypeEnumVO.UNKNOWN
                && reclassified.getIntent() != IntentTypeEnumVO.CONTINUE
                && reclassified.getIntent() != IntentTypeEnumVO.CHAT) {
            taskState.setRootIntent(reclassified.getIntent());
        }
        taskState.setLastFeedbackFailed(!success);
        if (success && actionSummary != null && !actionSummary.isBlank()) {
            List<String> steps = taskState.getSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                taskState.setSteps(steps);
            }
            steps.add(actionSummary);
            if (steps.size() > MAX_TASK_STEPS) {
                steps.remove(0);
            }
        }
        syncStepIndex(taskState);
        setTaskState(sessionId, taskState);
        return taskState;
    }

    /**
     * 回合正常收口 → 任务完成归档。
     * <p>
     * 由 LoopDecisionNode 在"回合正常结束且有工具执行且无失败标记"时调用；
     * 完成后下一条同意图消息在 syncTaskStateAfterClassification 中走"已完成 → 重建"分支。
     */
    public void markTaskCompleted(String sessionId) {
        TaskStateVO taskState = getTaskState(sessionId);
        if (taskState == null) {
            return;
        }
        taskState.setCompleted(true);
        taskState.setLastFeedbackFailed(false);
        setTaskState(sessionId, taskState);
    }

    /**
     * 回合异常中断 → 任务保持可续接但打上失败标记。
     * <p>
     * 由 LoopDecisionNode 在 stopReason=error 时调用；后续用户 "继续" 经
     * syncTaskStateAfterClassification 的 CONTINUE 分支清除标记续接。
     */
    public void markTaskInterrupted(String sessionId) {
        TaskStateVO taskState = getTaskState(sessionId);
        if (taskState == null || taskState.isCompleted()) {
            return;
        }
        taskState.setLastFeedbackFailed(true);
        setTaskState(sessionId, taskState);
    }

    /** 以留痕列表末尾重算步骤索引（steps 语义 = 任务推进留痕，见 TaskStateVO） */
    private void syncStepIndex(TaskStateVO taskState) {
        List<String> steps = taskState.getSteps();
        taskState.setCurrentStepIndex(steps == null || steps.isEmpty() ? 0 : steps.size() - 1);
    }

    /**
     * 清除指定会话的上下文。
     * <p>
     * 从内存 Map 中移除该会话的所有意图历史、任务态及连续失败计数。
     * 通常在会话结束或用户显式要求重置时使用。
     *
     * @param sessionId 要清除的会话 ID
     */
    public void clear(String sessionId) {
        contexts.remove(sessionId);
    }

    /**
     * 惰性清理过期会话，并在超出上限时淘汰最久未活跃的会话。
     * 在 getContext 入口触发，无需后台线程。
     * <p>
     * 两步策略：先按 TTL(30min) 移除过期项，再在总量超 MAX_SESSIONS(500) 时
     * 按 lastActiveTime 升序淘汰最旧的若干项。
     * <p>
     * 案例：
     * <pre>
     *   当前 510 个会话，最后活跃时间：
     *   session-A: lastActiveTime = 10分钟前
     *   session-B: lastActiveTime = 35分钟前（过期）
     *   session-C: lastActiveTime = 1小时前（过期）
     *   session-D: lastActiveTime = 5分钟前
     *
     *   执行步骤：
     *   1. 按 TTL 移除过期项 → 移除 B、C，剩余 508 个
     *   2. 总量仍 > 500 → 按 lastActiveTime 升序淘汰最旧 8 个
     *   3. 最终保留 500 个最活跃的会话
     * </pre>
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConversationContextVO>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConversationContextVO> e = it.next();
            if (now - e.getValue().getLastActiveTime() > SESSION_TTL_MS) {
                it.remove();
            }
        }

        // 超出上限时按 lastActiveTime 升序淘汰（最旧的先移除）
        if (contexts.size() > MAX_SESSIONS) {
            contexts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) ->
                            Long.compare(a.getLastActiveTime(), b.getLastActiveTime())))
                    .limit(contexts.size() - MAX_SESSIONS)
                    .forEach(e -> contexts.remove(e.getKey()));
        }
    }
}

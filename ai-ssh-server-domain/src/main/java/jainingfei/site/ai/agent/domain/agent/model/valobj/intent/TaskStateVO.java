package jainingfei.site.ai.agent.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedList;
import java.util.List;

/**
 * 任务态（支撑 CONTINUE 续接与多步运维任务的回合级状态机，V3.2 托管循环回合语义）。
 * <p>
 * 一次用户消息 = 一个回合（ADK Runner 一次 runAsync 内部可连续执行多个工具），
 * 任务态以"用户任务"为单位跨回合存活，由 ContextTracker 按 sessionId 缓存
 * （30min TTL / 500 会话上限，重启即失——任务态暂存内存，历史仍从 DB 恢复）。
 * <p>
 * 字段语义：
 * <pre>
 *   taskDescription  任务锚点：创建时的用户原始消息；复用/续接不覆盖（TaskProvider 依赖它防任务漂移）
 *   rootIntent       任务根意图：创建 = 分类意图；工具反馈重分类为业务意图时替换
 *   steps            任务推进留痕：创建时 [lastUserMessage]，每成功一个工具动作追加一条摘要
 *                    （上限 12 条滚动，丢最旧；动作摘要由调用方截断后传入）
 *   currentStepIndex 当前步骤下标 = steps.size()-1（每次写回重算，steps 为空时为 0）
 *   completed        回合正常收口且本回合有工具执行且无失败标记时，由 LoopDecisionNode 归档置位；
 *                    已完成任务再接同意图消息 → 走"重建新任务"分支（新锚点）
 *   lastFeedbackFailed 最近一次工具反馈失败/回合异常中断标记：失败置位、成功清位；
 *                    CONTINUE 续接/同意图复用也清位
 * </pre>
 *
 * <p>回合内状态流转（写路径三入口，详见 ContextTracker）：
 * <pre>
 *   AiCallNode 分类后（每回合一次）
 *     ├─ CONTINUE + 未完成任务   → 续接：清失败标记、校准索引（rootIntent 回填给会话意图）
 *     ├─ UNKNOWN / CHAT         → 跳过（不建不毁，闲聊不冲掉进行中任务）
 *     ├─ 业务意图 + (无任务/已完成/根意图变更) → 重建新任务（description/steps=本条消息）
 *     └─ 业务意图 + 同根意图未完成          → 复用并刷新（补空、清完成与失败标记）
 *         ▼
 *   ToolCallNode 每工具结果（一次回合可能多次）
 *     ├─ 成功 → 追加动作摘要留痕、清失败标记
 *     ├─ 失败 → lastFeedbackFailed=true
 *     └─ 重分类为业务意图 → 替换 rootIntent（不在此置 completed——见下）
 *         ▼
 *   LoopDecisionNode 回合收口（统一判定完成/中断）
 *     ├─ stopReason=error        → markTaskInterrupted（保持可续接 + 失败标记）
 *     ├─ 正常结束 && 有工具结果 && 无失败 → markTaskCompleted（任务达成归档）
 *     └─ 正常结束但无工具结果     → 原样保留（纯回答/追问确认，下回合同意图可复用续接）
 * </pre>
 *
 * <p>教学版差异：教学版在"首次工具成功反馈"即置 completed，这在托管循环下有缺陷——
 * 一个回合内模型会连跑多个工具，首次成功就把"等用户确认/未收尾"的任务误判完成；
 * 故 V3.2 将 completed 的判定收敛到 LoopDecisionNode 回合收口。
 *
 * <p>案例（"查下 nginx 502"）：
 * <pre>
 *   回合1 DIAGNOSE 命中 → 创建 { desc="查下 nginx 502", rootIntent=DIAGNOSE, steps=["查下 nginx 502"] }
 *       工具: tail nginx error.log（成功）→ steps+["tail error.log"]，index=1
 *       回合收口：无失败 → completed=true
 *   用户："再看看是不是 redis 导致的"（DIAGNOSE）
 *       已完成 → 重建 { desc="再看看是不是 redis 导致的", steps=[...], index=0 }
 *   用户："继续"（CONTINUE）→ 续接当前任务态，清失败标记，rootIntent 回填 DIAGNOSE
 * </pre>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskStateVO {

    /** 任务描述锚点（创建时的用户消息；复用/续接不覆盖） */
    private String taskDescription;

    /** 任务根意图（如 DIAGNOSE / DEPLOY / EXECUTE；重分类为业务意图时替换） */
    private IntentTypeEnumVO rootIntent;

    /** 任务推进留痕：首条用户消息 + 成功工具动作摘要（上限 12 条滚动） */
    @Builder.Default
    private List<String> steps = new LinkedList<>();

    /** 当前步骤下标 = steps.size()-1（每次写回重算，-1 表示尚未开始） */
    @Builder.Default
    private int currentStepIndex = -1;

    /** 任务是否完成归档（LoopDecisionNode 回合正常收口时置位） */
    @Builder.Default
    private boolean completed = false;

    /** 最近一次反馈是否失败/回合是否中断（成功、CONTINUE、复用会清位） */
    @Builder.Default
    private boolean lastFeedbackFailed = false;

}

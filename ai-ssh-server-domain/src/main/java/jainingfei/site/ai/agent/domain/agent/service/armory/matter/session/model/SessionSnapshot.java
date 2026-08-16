package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model;

import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量 Session 快照——CustomAdkSessionService 的内部存储单元。
 *
 * <p>相对 ADK 原生 Session 的简化：
 * <ul>
 *   <li>只保留运行期轻量数据，不做持久化落库</li>
 *   <li>事件列表用 CopyOnWriteArrayList（读多写少，并发安全）</li>
 *   <li>state 只同步最新运行态（mergeStateDelta），不保存整段执行历史</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SessionSnapshot {

    /** 会话三元组（appName / userId / sessionId） */
    private SessionKey sessionKey;

    /** 最新运行态（由 event.actions().stateDelta() 合并而来） */
    private State state;

    /** 已净化的原始事件列表（截断/剥离前缀/限轮次后） */
    @Builder.Default
    private List<Event> rawEvents = new CopyOnWriteArrayList<>();

    /** 最后更新时间 */
    private Instant lastUpdateTime;
}

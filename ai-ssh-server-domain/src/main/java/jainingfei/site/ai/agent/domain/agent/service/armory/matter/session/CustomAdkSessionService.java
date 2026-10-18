package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * 自定义 ADK Session 服务——放在 ADK Session 前面的"净化器 + 限流器 + 修剪器"。
 *
 * <p>为什么自己接管 Session：业务层（2-4 节起）已做动态 Prompt 上下文增强，
 * 富化消息适合发给模型做推理，但不适合被 ADK 原样存回：
 * <pre>
 *   1. 用户原始问题被动态后缀污染（原始消息 + "\n---\n" + [系统环境]… 原样落库）
 *   2. 相同上下文在业务层与框架层重复保存
 *   3. tool / assistant 长文本让 Session 持续膨胀
 *   4. 对话轮次越来越长，后续取历史成本越来越高
 * </pre>
 *
 * <p>本类只做<b>编排</b>，职责分别落在三个协作类上：
 * <pre>
 *   SessionStore       内存存储与读写原语（三级结构、快照、stateDelta、事件规范化）
 *   EventGovernor      按角色净化与限长（user 后缀剥离、tool 结构化截断、assistant marker 感知限长）
 *   CompactionSupport  压缩摘要事件的追加与剪枝、压缩关闭时的兜底 trim
 *   参数               SessionGovernanceProperties（ai.session-governance.*）
 * </pre>
 *
 * <p>与业务侧 ChatContextService 的分工：业务侧管"注入什么"（Provider + 裁剪），
 * 本类管"框架层存什么"（净化 + 限流）——两层都收敛，上下文才不打架。
 */
@Slf4j
@Component
public class CustomAdkSessionService implements BaseSessionService {

    private final SessionStore store;
    private final EventGovernor governor;
    private final CompactionSupport compaction;
    private final SessionGovernanceProperties properties;

    public CustomAdkSessionService(SessionStore store,
                                   EventGovernor governor,
                                   CompactionSupport compaction,
                                   SessionGovernanceProperties properties) {
        this.store = store;
        this.governor = governor;
        this.compaction = compaction;
        this.properties = properties;
    }

    @Override
    public Single<Session> createSession(String appName, String userId,
                                         ConcurrentMap<String, Object> initialState, String sessionId) {
        SessionSnapshot snapshot = store.createSnapshot(appName, userId, sessionId, initialState);
        return Single.just(store.toSession(snapshot, Optional.empty()));
    }

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId,
                                     Optional<GetSessionConfig> config) {
        SessionSnapshot snapshot = store.findSnapshot(appName, userId, sessionId);
        if (snapshot == null) {
            return Maybe.empty();
        }
        Optional<List<Event>> filteredEvents = config
                .filter(c -> c.numRecentEvents().isPresent() || c.afterTimestamp().isPresent())
                .map(c -> store.filterEvents(snapshot.getRawEvents(), c));
        return Maybe.just(store.toSession(snapshot, filteredEvents));
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.just(ListSessionsResponse.builder()
                .sessions(ImmutableList.copyOf(store.listSessions(appName, userId)))
                .build());
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        store.deleteSession(appName, userId, sessionId);
        // 会话真正删除时解绑 SSH 连接（回合末不再解绑，绑定要跨回合保留以支持"恢复上次服务器"）
        SshSessionBindings.unbindSession(sessionId);
        return Completable.complete();
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        SessionSnapshot snapshot = store.findSnapshot(appName, userId, sessionId);
        List<Event> events = snapshot == null ? List.of() : new ArrayList<>(snapshot.getRawEvents());
        return Single.just(ListEventsResponse.builder()
                .events(ImmutableList.copyOf(events))
                .build());
    }

    /**
     * 追加一条事件，写入前完成本类最核心的会话治理：
     * normalize（补时间戳/id）→ resolveRole → 按角色净化/截断 → 写入 → 合并 stateDelta → 裁剪轮次/条数。
     */
    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        SessionKey key = session.sessionKey();
        SessionSnapshot snapshot = store.findSnapshot(key.appName(), key.userId(), key.id());
        if (snapshot == null) {
            return Single.error(new IllegalStateException("session not found: " + key));
        }

        Event normalized = store.normalizeEvent(event);

        // 压缩摘要事件：框架 Contents 依赖 compaction 标记过滤被覆盖区间——
        // 走专属分支：不做角色净化/截断/合并；滚动摘要折叠后立即剪枝被覆盖的普通事件
        // 与旧压缩事件（内存有界），压缩事件本身永不被 trim 裁掉
        if (CompactionSupport.isCompactionEvent(normalized)) {
            compaction.appendCompactionEvent(snapshot, normalized);
            snapshot.setLastUpdateTime(store.resolveUpdateTime(normalized));
            syncToSession(session, snapshot, normalized);
            return Single.just(normalized);
        }

        String role = governor.resolveRole(normalized);
        if ("user".equalsIgnoreCase(role)) {
            normalized = governor.governUserEvent(normalized);
        } else if ("tool".equalsIgnoreCase(role)) {
            normalized = governor.governToolEvent(normalized);
        } else if (EventGovernor.isAssistantRole(role)) {
            normalized = governor.governAssistantEvent(normalized);
        }

        snapshot.getRawEvents().add(normalized);
        store.mergeStateDelta(snapshot, normalized);
        if (!properties.getCompact().isEnabled()) {
            // 压缩开启后长度治理由压缩引擎承担（滚动摘要 + 剪枝）；丢头 trim 会与
            // "折叠回填"互斥（把摘要前的原文或摘要本身裁掉），仅作未开启时的旧语义
            compaction.trim(snapshot.getRawEvents());
        }
        snapshot.setLastUpdateTime(store.resolveUpdateTime(normalized));
        syncToSession(session, snapshot, normalized);

        return Single.just(normalized);
    }

    /**
     * 同步到 Runner 传入的 session 对象（ADK 1.9 起 Contents 等每步从该对象读事件；
     * 官方 InMemorySessionService 同样直接 add，而我们治理的快照与 getSession 拷贝的
     * events 是不同实例，须双写）。
     */
    private static void syncToSession(Session session, SessionSnapshot snapshot, Event event) {
        if (session.events() != snapshot.getRawEvents()) {
            session.events().add(event);
        }
    }
}

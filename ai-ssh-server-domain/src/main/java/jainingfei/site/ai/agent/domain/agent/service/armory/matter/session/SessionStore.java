package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话存储——内存里的 {@code appName → userId → sessionId → 快照} 三级结构，以及所有读写原语。
 *
 * <p>本类只负责"存在哪、怎么取、怎么变"：
 * <pre>
 *   创建/查询/删除会话、快照 → ADK {@link Session} 的视图转换、事件子集过滤
 *   事件规范化（补时间戳与 id）、stateDelta 合并、会话最近更新时间
 * </pre>
 *
 * <p>治理（净化/截断）在 {@link EventGovernor}，压缩与裁剪在 {@link CompactionSupport}；
 * 三者由 {@link CustomAdkSessionService} 在 appendEvent 里编排——这样"换存储"（未来落库）
 * 只需要替换本类。
 *
 * <p>双写语义保持不变：治理后的快照是权威副本，同时把治理过的事件写回 Runner 传入的
 * {@link Session} 对象（ADK 1.9 起 Contents 等每步从该对象读事件）。
 */
@Slf4j
@Component
public class SessionStore {

    /** appName → userId → sessionId → SessionSnapshot */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>>> sessions =
            new ConcurrentHashMap<>();

    /** 创建会话快照（sessionId 为空时生成 UUID），并登记到三级结构 */
    public SessionSnapshot createSnapshot(String appName, String userId, String sessionId,
                                          ConcurrentMap<String, Object> initialState) {
        String finalSessionId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;

        State state = new State(initialState == null ? new ConcurrentHashMap<>() : initialState);
        SessionSnapshot snapshot = SessionSnapshot.builder()
                .sessionKey(new SessionKey(appName, userId, finalSessionId))
                .state(state)
                .lastUpdateTime(Instant.now())
                .build();

        sessions.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(finalSessionId, snapshot);

        log.debug("自定义 Session 创建: appName={} userId={} sessionId={}", appName, userId, finalSessionId);
        return snapshot;
    }

    /** 查找快照；不存在返回 null */
    public SessionSnapshot findSnapshot(String appName, String userId, String sessionId) {
        return sessions.getOrDefault(appName, new ConcurrentHashMap<>())
                .getOrDefault(userId, new ConcurrentHashMap<>())
                .get(sessionId);
    }

    /** 快照 → ADK Session 视图（eventsOverride 为空时用快照的全量事件） */
    public Session toSession(SessionSnapshot snapshot, Optional<List<Event>> eventsOverride) {
        List<Event> events = eventsOverride.orElseGet(snapshot::getRawEvents);
        Session session = Session.builder(snapshot.getSessionKey())
                .state(snapshot.getState())
                .events(events)
                .build();
        session.lastUpdateTime(snapshot.getLastUpdateTime());
        return session;
    }

    /** 列出某用户在某应用下的全部会话 */
    public List<Session> listSessions(String appName, String userId) {
        List<Session> sessionList = new ArrayList<>();
        for (SessionSnapshot snapshot : userSessions(appName, userId).values()) {
            sessionList.add(toSession(snapshot, Optional.empty()));
        }
        return sessionList;
    }

    /** 删除会话 */
    public void deleteSession(String appName, String userId, String sessionId) {
        userSessions(appName, userId).remove(sessionId);
    }

    /** 按 GetSessionConfig 过滤事件子集（numRecentEvents / afterTimestamp） */
    public List<Event> filterEvents(List<Event> events, GetSessionConfig config) {
        List<Event> result = new ArrayList<>(events);
        if (config.afterTimestamp().isPresent()) {
            Instant after = config.afterTimestamp().get();
            result.removeIf(e -> e.timestamp() <= after.toEpochMilli());
        }
        if (config.numRecentEvents().isPresent()) {
            int n = Math.min(config.numRecentEvents().get(), result.size());
            result = new ArrayList<>(result.subList(result.size() - n, result.size()));
        }
        return result;
    }

    /** 规范化 Event：补齐时间戳和事件 ID */
    public Event normalizeEvent(Event event) {
        Event normalized = event.toBuilder().build();
        if (normalized.timestamp() <= 0) {
            normalized.setTimestamp(System.currentTimeMillis());
        }
        if (normalized.id() == null || normalized.id().isBlank()) {
            normalized.setId(Event.generateEventId());
        }
        return normalized;
    }

    /** 合并 event.actions().stateDelta() 到 snapshot.state——只同步最新运行态 */
    public void mergeStateDelta(SessionSnapshot snapshot, Event event) {
        if (event.actions() == null || event.actions().stateDelta() == null
                || event.actions().stateDelta().isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : event.actions().stateDelta().entrySet()) {
            if (State.REMOVED.equals(entry.getValue())) {
                snapshot.getState().remove(entry.getKey());
            } else {
                snapshot.getState().put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 会话最近更新时间：优先事件时间戳 */
    public Instant resolveUpdateTime(Event event) {
        if (event.timestamp() > 0) {
            return Instant.ofEpochMilli(event.timestamp());
        }
        return Instant.now();
    }

    private ConcurrentMap<String, SessionSnapshot> userSessions(String appName, String userId) {
        return sessions.getOrDefault(appName, new ConcurrentHashMap<>())
                .getOrDefault(userId, new ConcurrentHashMap<>());
    }
}

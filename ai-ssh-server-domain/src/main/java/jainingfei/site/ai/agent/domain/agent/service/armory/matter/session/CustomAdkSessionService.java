package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 自定义 ADK Session 服务——放在 ADK Session 前面的"净化器 + 限流器 + 修剪器"。
 *
 * <p>为什么自己接管 Session：业务层（2-4 节起）已经做了动态 Prompt 上下文增强，
 * 富化消息适合发给模型做推理，但并不适合被 ADK 原样存回 Session：
 * <pre>
 *   1. 用户原始问题被动态前缀污染（[系统环境]...\n---\n原始消息 原样落库）
 *   2. 相同上下文在业务层和框架层重复保存
 *   3. tool / assistant 长文本让 Session 持续膨胀
 *   4. 对话轮次越来越长，后续取历史成本越来越高
 * </pre>
 *
 * <p>治理手段（appendEvent 时按角色执行）：
 * <ul>
 *   <li>user → sanitizeUserEvent：剥离动态前缀，只留原始用户问题（+工具结果），并截断到 MAX_USER_TEXT</li>
 *   <li>tool → truncateEventText(1000)</li>
 *   <li>assistant/model → truncateEventText(2000)</li>
 *   <li>所有事件 → trimEvents：最多保留 MAX_TURNS 轮、MAX_EVENTS 条</li>
 *   <li>stateDelta → mergeStateDelta：只同步最新运行态，不保存整段历史</li>
 * </ul>
 *
 * <p>与业务侧 ChatContextService 的分工：业务侧管"注入什么"（Provider + 裁剪），
 * 本类管"框架层存什么"（净化 + 限流）——两层都收敛，上下文才不打架。
 */
@Slf4j
@Component
public class CustomAdkSessionService implements BaseSessionService {

    /** Session 最多保留的 event 条数，防止框架层历史无限膨胀 */
    private static final int MAX_EVENTS = 20;

    /** assistant/model 文本的最大保留长度 */
    private static final int MAX_ASSISTANT_TEXT = 2000;

    /** tool 结果文本的最大保留长度 */
    private static final int MAX_TOOL_TEXT = 1000;

    /** user 事件的最大保留长度（剥离前缀后；富化消息可能内嵌工具结果） */
    private static final int MAX_USER_TEXT = 4000;

    /** 最多保留的用户轮次数；轮次是比"消息条数"更自然的对话单位 */
    private static final int MAX_TURNS = 4;

    /** appName → userId → sessionId → SessionSnapshot */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>>> sessions =
            new ConcurrentHashMap<>();

    @Override
    public Single<Session> createSession(String appName, String userId,
                                         ConcurrentMap<String, Object> initialState, String sessionId) {
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
        return Single.just(toSession(snapshot, Optional.empty()));
    }

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId,
                                     Optional<GetSessionConfig> config) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        if (snapshot == null) {
            return Maybe.empty();
        }

        Optional<List<Event>> filteredEvents = config
                .filter(c -> c.numRecentEvents().isPresent() || c.afterTimestamp().isPresent())
                .map(c -> filterEvents(snapshot.getRawEvents(), c));
        return Maybe.just(toSession(snapshot, filteredEvents));
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        ConcurrentMap<String, SessionSnapshot> userSessions =
                sessions.getOrDefault(appName, new ConcurrentHashMap<>())
                        .getOrDefault(userId, new ConcurrentHashMap<>());
        List<Session> sessionList = new ArrayList<>();
        for (SessionSnapshot snapshot : userSessions.values()) {
            sessionList.add(toSession(snapshot, Optional.empty()));
        }
        return Single.just(ListSessionsResponse.builder()
                .sessions(ImmutableList.copyOf(sessionList))
                .build());
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        ConcurrentMap<String, SessionSnapshot> userSessions =
                sessions.getOrDefault(appName, new ConcurrentHashMap<>())
                        .getOrDefault(userId, new ConcurrentHashMap<>());
        userSessions.remove(sessionId);
        return Completable.complete();
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
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
        SessionSnapshot snapshot = findSnapshot(key.appName(), key.userId(), key.id());
        if (snapshot == null) {
            return Single.error(new IllegalStateException("session not found: " + key));
        }

        Event normalized = normalizeEvent(event);
        String role = resolveRole(normalized);
        if ("user".equalsIgnoreCase(role)) {
            normalized = sanitizeUserEvent(normalized);
        } else if ("tool".equalsIgnoreCase(role)) {
            normalized = truncateEventText(normalized, MAX_TOOL_TEXT);
        } else if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
            normalized = truncateEventText(normalized, MAX_ASSISTANT_TEXT);
        }

        snapshot.getRawEvents().add(normalized);
        mergeStateDelta(snapshot, normalized);
        trimEvents(snapshot.getRawEvents());
        snapshot.setLastUpdateTime(resolveUpdateTime(normalized));

        return Single.just(normalized);
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    private SessionSnapshot findSnapshot(String appName, String userId, String sessionId) {
        ConcurrentMap<String, SessionSnapshot> userSessions =
                sessions.getOrDefault(appName, new ConcurrentHashMap<>())
                        .getOrDefault(userId, new ConcurrentHashMap<>());
        return userSessions.get(sessionId);
    }

    private Session toSession(SessionSnapshot snapshot, Optional<List<Event>> eventsOverride) {
        List<Event> events = eventsOverride.orElseGet(snapshot::getRawEvents);
        Session session = Session.builder(snapshot.getSessionKey())
                .state(snapshot.getState())
                .events(events)
                .build();
        session.lastUpdateTime(snapshot.getLastUpdateTime());
        return session;
    }

    /** 按 GetSessionConfig 过滤事件子集（numRecentEvents / afterTimestamp） */
    private List<Event> filterEvents(List<Event> events, GetSessionConfig config) {
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
    private Event normalizeEvent(Event event) {
        Event normalized = event.toBuilder().build();
        if (normalized.timestamp() <= 0) {
            normalized.setTimestamp(System.currentTimeMillis());
        }
        if (normalized.id() == null || normalized.id().isBlank()) {
            normalized.setId(Event.generateEventId());
        }
        return normalized;
    }

    /** 解析事件角色：优先 content.role，回退 author。角色判断准确，净化/截断/裁剪才准确 */
    private String resolveRole(Event event) {
        return event.content().flatMap(Content::role).orElse(event.author());
    }

    /**
     * 对 user 事件做净化：剥离动态 Prompt 前缀，只保留原始用户问题（及内嵌工具结果）。
     *
     * <p>环境信息、最近命令、关键事件等内容业务层已维护一份，
     * 不应再在框架层 Session 里无限重复保存。
     */
    private Event sanitizeUserEvent(Event event) {
        String text = event.stringifyContent();
        if (text == null || text.isBlank()) {
            return event;
        }

        String actualUserMessage = stripDynamicPrefix(text);
        if (actualUserMessage.length() > MAX_USER_TEXT) {
            actualUserMessage = actualUserMessage.substring(0, MAX_USER_TEXT) + "...";
        }
        if (actualUserMessage.equals(text)) {
            return event;
        }

        Event sanitized = event.toBuilder().build();
        Content content = sanitized.content().orElse(Content.builder().role("user").build());
        String role = content.role().orElse("user");
        sanitized.setContent(Content.builder()
                .role(role)
                .parts(List.of(Part.fromText(actualUserMessage)))
                .build());
        return sanitized;
    }

    /**
     * 从文本中剥离动态注入前缀。
     *
     * <p>优先按 {@code \n---\n} 分隔线切分（富化消息的标准格式）；
     * 若没有分隔线，再根据典型前缀段落标签做启发式判断。
     */
    private String stripDynamicPrefix(String text) {
        if (text.contains("\n---\n")) {
            String[] parts = text.split("\\n---\\n", 2);
            return parts.length == 2 ? parts[1].trim() : text;
        }

        boolean looksLikeInjectedPrefix = text.startsWith("[系统环境]")
                || text.startsWith("[最近执行的命令]")
                || text.startsWith("[关键事件]")
                || text.startsWith("[当前任务]")
                || text.startsWith("[工具执行摘要]");
        if (!looksLikeInjectedPrefix) {
            return text;
        }

        int splitIndex = text.lastIndexOf('\n');
        if (splitIndex < 0 || splitIndex >= text.length() - 1) {
            return text;
        }
        String tail = text.substring(splitIndex + 1).trim();
        return tail.isEmpty() ? text : tail;
    }

    /** 截断过长的事件文本——框架层 Session 保留"够用信息"，不无上限保存完整原文 */
    private Event truncateEventText(Event event, int maxLength) {
        String text = event.stringifyContent();
        if (text == null || text.length() <= maxLength) {
            return event;
        }

        Event truncated = event.toBuilder().build();
        Content content = truncated.content().orElse(null);
        String role = content == null ? "assistant" : content.role().orElse("assistant");
        truncated.setContent(Content.builder()
                .role(role)
                .parts(List.of(Part.fromText(text.substring(0, maxLength) + "...")))
                .build());
        return truncated;
    }

    /** 合并 event.actions().stateDelta() 到 snapshot.state——只同步最新运行态 */
    private void mergeStateDelta(SessionSnapshot snapshot, Event event) {
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

    /** 裁剪事件列表：先按轮次裁剪，再按总条数兜底 */
    private void trimEvents(List<Event> events) {
        trimByTurn(events);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * 按用户轮次裁剪事件——从后往前回收最近消息，每遇到一条 user 消息计一轮，
     * 累计 MAX_TURNS 轮后停止。保留下的是"完整的最近几轮"，而不是按条数硬裁的碎片。
     */
    private void trimByTurn(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        LinkedList<Event> trimmed = new LinkedList<>();
        int userTurnCount = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event current = events.get(i);
            trimmed.addFirst(current);
            if ("user".equalsIgnoreCase(resolveRole(current))) {
                userTurnCount++;
                if (userTurnCount >= MAX_TURNS) {
                    break;
                }
            }
        }
        events.clear();
        events.addAll(trimmed);
    }

    private Instant resolveUpdateTime(Event event) {
        if (event.timestamp() > 0) {
            return Instant.ofEpochMilli(event.timestamp());
        }
        return Instant.now();
    }
}

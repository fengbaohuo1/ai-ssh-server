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
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 *   1. 用户原始问题被动态后缀污染（原始消息 + "\n---\n" + [系统环境]... 原样落库）
 *   2. 相同上下文在业务层和框架层重复保存
 *   3. tool / assistant 长文本让 Session 持续膨胀
 *   4. 对话轮次越来越长，后续取历史成本越来越高
 * </pre>
 *
 * <p>治理手段（appendEvent 时按角色执行；上限经 ai.session-governance.* 可配置）：
 * <ul>
 *   <li>user → sanitizeUserEvent：剥离动态后缀（2-9 起动态段在分隔线之后），只留原始用户问题（+工具结果），并截断到上限</li>
 *   <li>tool → truncateEventText(1000)</li>
 *   <li>assistant/model → truncateEventText(2000)</li>
 *   <li>所有事件 → trimEvents：最多保留 max-turns 轮、max-events 条</li>
 *   <li>stateDelta → mergeStateDelta：只同步最新运行态，不保存整段历史</li>
 * </ul>
 *
 * <p>与业务侧 ChatContextService 的分工：业务侧管"注入什么"（Provider + 裁剪），
 * 本类管"框架层存什么"（净化 + 限流）——两层都收敛，上下文才不打架。
 */
@Slf4j
@Component
public class CustomAdkSessionService implements BaseSessionService {

    /** Session 最多保留的 event 条数，防止框架层历史无限膨胀（可配置，默认 20） */
    @Value("${ai.session-governance.max-events:20}")
    private int maxEvents;

    /** assistant/model 文本的最大保留长度（可配置，默认 2000） */
    @Value("${ai.session-governance.max-assistant-text:2000}")
    private int maxAssistantText;

    /** tool 结果文本的最大保留长度（可配置，默认 1000） */
    @Value("${ai.session-governance.max-tool-text:1000}")
    private int maxToolText;

    /** user 事件的最大保留长度（剥离动态段后；富化消息可能内嵌工具结果；可配置，默认 4000） */
    @Value("${ai.session-governance.max-user-text:4000}")
    private int maxUserText;

    /** 最多保留的用户轮次数（可配置，默认 4）；轮次是比"消息条数"更自然的对话单位 */
    @Value("${ai.session-governance.max-turns:4}")
    private int maxTurns;

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
            normalized = truncateEventText(normalized, maxToolText);
        } else if (isAssistantRole(role)) {
            normalized = truncateEventText(normalized, maxAssistantText);
            // 流式 assistant 碎片合并：FixedSpringAI 每收到一个流式 chunk 就产出一个
            // LlmResponse → ADK 落一条 assistant 事件。若不合并，一条完整回复会被碎成
            // 几十条 1-3 字的事件——既撑爆 MAX_EVENTS 把早期 user 消息削掉，又让请求出现
            // "连续 assistant 碎片、无 user 间隔"，触发 DeepSeek thinking 模式 400
            // （reasoning_content in thinking mode must be passed back）。
            // 合并只作用于存储（改写上一条事件文本）；返回给调用方的仍是本条事件，
            // 流式 SSE 推送与 AiCallNode 逐事件消费不受影响。
            if (mergeIntoPreviousAssistant(snapshot, normalized)) {
                mergeStateDelta(snapshot, normalized);
                snapshot.setLastUpdateTime(resolveUpdateTime(normalized));
                return Single.just(normalized);
            }
        }

        snapshot.getRawEvents().add(normalized);
        mergeStateDelta(snapshot, normalized);
        trimEvents(snapshot.getRawEvents());
        snapshot.setLastUpdateTime(resolveUpdateTime(normalized));

        return Single.just(normalized);
    }

    /** assistant/model 均视为助手消息角色 */
    private static boolean isAssistantRole(String role) {
        return "assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role);
    }

    /**
     * 把一条流式 assistant 碎片文本合并进会话里上一条 assistant 事件（无则不动）。
     * <p>合并后重新应用单条上限（head/tail 保留，保首尾丢中段）。
     *
     * @return true = 已并入上一条（调用方不要再追加新事件）
     */
    private boolean mergeIntoPreviousAssistant(SessionSnapshot snapshot, Event incoming) {
        List<Event> events = snapshot.getRawEvents();
        if (events.isEmpty()) {
            return false;
        }
        Event prev = events.get(events.size() - 1);
        if (!isAssistantRole(resolveRole(prev))) {
            return false;
        }
        String prevText = prev.stringifyContent();
        String incText = incoming.stringifyContent();
        if (prevText == null || incText == null || incText.isEmpty()) {
            return false;
        }
        String merged = prevText + incText;
        if (merged.length() > maxAssistantText) {
            merged = TextRetentionUtil.retainHeadTail(merged, maxAssistantText);
        }
        Content content = prev.content().orElse(Content.builder().role(resolveRole(prev)).build());
        String role = content.role().orElse(resolveRole(prev));
        prev.setContent(Content.builder().role(role).parts(List.of(Part.fromText(merged))).build());
        return true;
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
     * 对 user 事件做净化：剥离动态消息后缀，只保留原始用户问题（及内嵌工具结果）。
     *
     * <p>这一步是"避免上下文重复"的关键动作之一。
     * 因为环境信息、最近命令、关键事件等内容，业务层已经维护了一份，
     * 不应再在框架层 Session 里无限重复保存。
     *
     * <p>案例（2-9 缓存友好布局：动态段在分隔线之后）：
     * <pre>
     *   输入：
     *   请继续查看 error.log
     *   [上一轮工具执行结果]
     *   $ tail -n 50 error.log
     *   ...
     *   ---
     *   [系统环境]
     *   系统: Linux
     *   [关键事件]
     *   - permission denied
     *
     *   输出：
     *   请继续查看 error.log
     *   [上一轮工具执行结果]
     *   $ tail -n 50 error.log
     *   ...
     * </pre>
     */
    private Event sanitizeUserEvent(Event event) {
        String text = event.stringifyContent();
        if (text == null || text.isBlank()) {
            return event;
        }

        String actualUserMessage = stripDynamicSuffix(text);
        if (actualUserMessage.length() > maxUserText) {
            actualUserMessage = actualUserMessage.substring(0, maxUserText) + "...";
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
     * 从文本中剥离动态注入后缀。
     *
     * <p>按最后一个 {@code \n---\n} 分隔线切分并保留分隔线之前的部分——
     * 动态段自身不含分隔线，而用户原文/工具输出可能含分隔线，取最后一个最稳妥；
     * 且要求分隔线之后的内容以动态段典型标记开头（防御"动态段为空时工具输出自带
     * 分隔线"的误切）。若没有分隔线，再根据典型后缀段落标签做启发式判断。
     *
     * <p>案例 1（第 1 轮富化消息）：
     * <pre>
     *   帮我分析日志
     *   ---
     *   [系统环境]
     *   ...
     *
     *   结果：帮我分析日志
     * </pre>
     *
     * <p>案例 2（后续轮：工具结果块在分隔线之前，属稳定段，随净化一并保留）：
     * <pre>
     *   再试一次 ls /var/log
     *   [上一轮工具执行结果]
     *   $ ls /var/log
     *   ...
     *   ---
     *   [关键事件]
     *   - 工具执行失败
     *
     *   结果：再试一次 ls /var/log
     *   [上一轮工具执行结果]
     *   $ ls /var/log
     *   ...
     * </pre>
     *
     * @param text 原始文本
     * @return 剥离后缀后的文本
     */
    private String stripDynamicSuffix(String text) {
        int sepIndex = text.lastIndexOf("\n---\n");
        if (sepIndex >= 0 && isDynamicTail(text.substring(sepIndex + "\n---\n".length()))) {
            String head = text.substring(0, sepIndex).trim();
            return head.isEmpty() ? text : head;
        }

        boolean looksLikeInjectedSuffix = text.endsWith("[系统环境]")
                || text.endsWith("[最近执行的命令]")
                || text.endsWith("[关键事件]")
                || text.endsWith("[当前任务]")
                || text.endsWith("[工具执行摘要]");
        if (!looksLikeInjectedSuffix) {
            return text;
        }

        int splitIndex = text.lastIndexOf('\n');
        if (splitIndex < 0) {
            return text;
        }
        String head = text.substring(0, splitIndex).trim();
        return head.isEmpty() ? text : head;
    }

    /**
     * 分隔线之后的"动态段"必须以典型段标记开头（全量/增量/意图修正三类），
     * 否则视为用户原文或工具输出自带的分隔线，不剥离。
     */
    private static boolean isDynamicTail(String tail) {
        String t = tail.trim();
        return t.startsWith("[系统环境]")
                || t.startsWith("[最近执行的命令]")
                || t.startsWith("[当前任务]")
                || t.startsWith("[用户意图]")
                || t.startsWith("[长期记忆]")
                || t.startsWith("[关键事件]")
                || t.startsWith("[工具执行摘要]")
                || t.startsWith("[本轮新增命令]")
                || t.startsWith("[新增关键事件]")
                || t.startsWith("[工具执行记录]")
                || t.startsWith("[用户意图已修正]");
    }

    /** 截断过长的事件文本——head/tail 保留 + 省略计数，框架层 Session 保留"够用信息"，不无上限保存完整原文 */
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
                .parts(List.of(Part.fromText(TextRetentionUtil.retainHeadTail(text, maxLength))))
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
        while (events.size() > maxEvents) {
            events.remove(0);
        }
    }

    /**
     * 按用户轮次裁剪事件——从后往前回收最近消息，每遇到一条 user 消息计一轮，
     * 累计 maxTurns 轮后停止。保留下的是"完整的最近几轮"，而不是按条数硬裁的碎片。
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
                if (userTurnCount >= maxTurns) {
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

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
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
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
 *   <li>tool → truncateEventText(4000)：FunctionResponse 事件会进入托管循环的下一轮 LLM 请求</li>
 *   <li>assistant/model → truncateEventText(2000)：V3 partial 语义下一次 LLM 调用只有一条聚合事件落库，
 *       无需再碎片合并；限长仍跳过隐藏草稿标记区</li>
 *   <li>压缩事件（actions.compaction）→ 专属分支：不做净化/截断/合并，追加后剪枝被覆盖的普通事件
 *       与旧压缩事件；压缩开启（compact.enabled）时丢头 trim 停用，长度治理交给压缩引擎</li>
 *   <li>所有事件 → trimEvents（压缩关闭时）：最多保留 max-turns 轮、max-events 条</li>
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

    /** tool 结果文本的最大保留长度（可配置，默认 4000——托管循环下 FunctionResponse 事件
     * 会原样进入下一轮 LLM 请求（Contents 从会话事件重建），与工具返回前截断对齐） */
    @Value("${ai.session-governance.max-tool-text:4000}")
    private int maxToolText;

    /** user 事件的最大保留长度（剥离动态段后；富化消息可能内嵌工具结果；可配置，默认 4000） */
    @Value("${ai.session-governance.max-user-text:4000}")
    private int maxUserText;

    /** 最多保留的用户轮次数（可配置，默认 4）；轮次是比"消息条数"更自然的对话单位 */
    @Value("${ai.session-governance.max-turns:4}")
    private int maxTurns;

    /** W4 压缩开关（ai.session-governance.compact.enabled）：开启后长度治理交给 ADK
     * 事件压缩引擎（滚动摘要 + 覆盖区间剪枝），丢头 trim 停用（压缩事件不可被裁掉） */
    @Value("${ai.session-governance.compact.enabled:false}")
    private boolean compactEnabled;

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

        // 压缩摘要事件（W4）：框架 Contents 依赖 compaction 标记过滤被覆盖区间——
        // 走专属分支：不做角色净化/截断/合并；滚动摘要折叠后立即剪枝被覆盖的普通事件
        // 与旧压缩事件（内存有界），压缩事件本身永不被 trim 裁掉
        if (isCompactionEvent(normalized)) {
            appendCompactionEvent(snapshot, normalized);
            snapshot.setLastUpdateTime(resolveUpdateTime(normalized));
            if (session.events() != snapshot.getRawEvents()) {
                session.events().add(normalized);
            }
            return Single.just(normalized);
        }

        String role = resolveRole(normalized);
        if ("user".equalsIgnoreCase(role)) {
            normalized = sanitizeUserEvent(normalized);
        } else if ("tool".equalsIgnoreCase(role)) {
            // tool 事件的结构化截断：FunctionResponse parts 必须保留（历史重建要按 id/name
            // 回放 tool 消息配对），只对响应里的字符串字段做 head/tail 限长
            normalized = truncateToolEvent(normalized);
        } else if (isAssistantRole(role)) {
            // assistant 事件可能携带"隐藏草稿标记"（DeepSeek thinking 草稿随文本落库）：
            // 限长只作用于可见正文，标记区（base64 草稿）不参与 head/tail 截断，防止标记损坏；
            // 带 FunctionCall parts 的事件只限长文本部分，绝不 stringify 破坏工具调用结构
            normalized = truncateAssistantEvent(normalized);
        }

        snapshot.getRawEvents().add(normalized);
        mergeStateDelta(snapshot, normalized);
        if (!compactEnabled) {
            // W4 压缩开启后长度治理由压缩引擎承担（滚动摘要 + 剪枝）；丢头 trim 会与
            // "折叠回填"互斥（把摘要前的原文或摘要本身裁掉），仅作未开启时的旧语义
            trimEvents(snapshot.getRawEvents());
        }
        snapshot.setLastUpdateTime(resolveUpdateTime(normalized));
        // 同步到 Runner 传入的 session 对象（ADK 1.9 起 Contents 等每步从该对象读事件；
        // 官方 InMemorySessionService 同样通过 BaseSessionService.super.appendEvent 直接 add，
        // 而我们治理的 snapshot 与 getSession 拷贝的 events 是不同实例，须双写）
        if (session.events() != snapshot.getRawEvents()) {
            session.events().add(normalized);
        }

        return Single.just(normalized);
    }

    /** assistant/model 均视为助手消息角色 */
    private static boolean isAssistantRole(String role) {
        return "assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role);
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

    /**
     * 解析事件角色：<b>先看结构化 part，再看 content.role，最后回退 author</b>。
     *
     * <p>为什么不能只看 content.role：ADK 的 {@code Functions.buildResponseEvent} 把工具结果事件的
     * content.role 设成 <b>"user"</b>（Gemini 风格：函数结果算作 user 轮内容）。只按 role 判断会把
     * 工具结果当"用户消息"送进 {@link #sanitizeUserEvent}——一旦其 stringify 文本超过 user 上限，
     * 就会被重建成<b>纯文本 user 事件</b>，FunctionResponse part（id/name/response）随之丢失，
     * 下一轮历史重建就凑不出 tool 消息与 assistant tool_calls 的配对，provider 直接 400：
     * <pre>
     *   "An assistant message with 'tool_calls' must be followed by tool messages
     *    responding to each 'tool_call_id'."
     * </pre>
     * 因此结构化优先：带 FunctionResponse 的事件一律是工具结果，带 FunctionCall 的按助手事件处理
     * （只影响限长方式，不影响角色语义）。
     */
    private String resolveRole(Event event) {
        List<Part> parts = event.content().flatMap(Content::parts).orElse(List.of());
        if (parts.stream().anyMatch(p -> p.functionResponse().isPresent())) {
            return "tool";
        }
        if (parts.stream().anyMatch(p -> p.functionCall().isPresent())) {
            return "model";
        }
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

    /**
     * assistant 事件限长（结构化版本，V3）：隐藏草稿标记区（DeepSeek reasoning_content
     * 随文本落库）不参与 head/tail 截断，避免截断损坏 base64 标记导致下轮无法解析草稿；
     * 携带 FunctionCall parts 的事件只限长文本部分，FunctionCall parts 原样保留——
     * 历史重建需要"完整工具调用结构 + 草稿标记"才能回传 DeepSeek，stringify 成纯文本会破坏配对。
     */
    private Event truncateAssistantEvent(Event event) {
        String text = event.stringifyContent();
        if (text == null || text.length() <= maxAssistantText) {
            return event;
        }
        List<Part> parts = event.content().flatMap(Content::parts).orElse(List.of());
        boolean hasFunctionCalls = parts.stream().anyMatch(p -> p.functionCall().isPresent());

        Event truncated = event.toBuilder().build();
        Content content = truncated.content().orElse(null);
        String role = content == null ? "assistant" : content.role().orElse("assistant");

        if (!hasFunctionCalls) {
            // 纯文本事件：整条 marker 感知 head/tail 限长
            truncated.setContent(Content.builder()
                    .role(role)
                    .parts(List.of(Part.fromText(limitAssistantText(text))))
                    .build());
            return truncated;
        }

        // 带 FunctionCall：只限长文本 part（marker 区不动），工具调用 parts 原样保留
        StringBuilder visible = new StringBuilder();
        for (Part p : parts) {
            p.text().ifPresent(visible::append);
        }
        List<Part> newParts = new ArrayList<>();
        if (visible.length() > maxAssistantText) {
            newParts.add(Part.fromText(limitAssistantText(visible.toString())));
        }
        for (Part p : parts) {
            if (p.functionCall().isPresent()) {
                newParts.add(p);
            }
        }
        truncated.setContent(Content.builder()
                .role(role)
                .parts(newParts.isEmpty() ? List.of(Part.fromText(limitAssistantText(text))) : newParts)
                .build());
        return truncated;
    }

    /** 文本版：草稿标记区原样保留，仅对可见正文做 head/tail 限长 */
    private String limitAssistantText(String text) {
        String[] parsed = ReasoningTagUtil.parse(text);
        if (parsed[0] == null || parsed[1] == null) {
            return TextRetentionUtil.retainHeadTail(text, maxAssistantText);
        }
        String visible = parsed[1];
        String limitedVisible = visible.length() > maxAssistantText
                ? TextRetentionUtil.retainHeadTail(visible, maxAssistantText)
                : visible;
        return ReasoningTagUtil.wrap(parsed[0]) + limitedVisible;
    }

    /**
     * tool 事件结构化截断（V3 托管循环）：FunctionResponse 事件必须保留 part 结构
     * （id/name/response map）——历史重建按 part 回放成 spring-ai ToolResponseMessage 与
     * assistant tool_calls 配对；stringify 成文本会把 JSON 截断成非法内容并丢失配对。
     * 超预算时只对 response map 里的字符串字段做 head/tail 限长（先缩最长的字段）。
     */
    private Event truncateToolEvent(Event event) {
        String text = event.stringifyContent();
        if (text == null || text.length() <= maxToolText) {
            return event;
        }
        List<Part> parts = event.content().flatMap(Content::parts).orElse(List.of());
        boolean hasFunctionResponses = parts.stream().anyMatch(p -> p.functionResponse().isPresent());
        if (!hasFunctionResponses) {
            Event truncated = event.toBuilder().build();
            Content content = truncated.content().orElse(null);
            String role = content == null ? "tool" : content.role().orElse("tool");
            truncated.setContent(Content.builder()
                    .role(role)
                    .parts(List.of(Part.fromText(TextRetentionUtil.retainHeadTail(text, maxToolText))))
                    .build());
            return truncated;
        }

        List<Part> newParts = new ArrayList<>();
        for (Part p : parts) {
            if (p.functionResponse().isPresent()) {
                com.google.genai.types.FunctionResponse fr = p.functionResponse().get();
                Map<String, Object> resp = new java.util.LinkedHashMap<>(fr.response().orElse(Map.of()));
                capResponseStrings(resp, maxToolText);
                newParts.add(Part.builder()
                        .functionResponse(fr.toBuilder().response(resp).build())
                        .build());
            } else {
                newParts.add(p);
            }
        }
        Event truncated = event.toBuilder().build();
        Content content = truncated.content().orElse(null);
        String role = content == null ? "tool" : content.role().orElse("tool");
        truncated.setContent(Content.builder()
                .role(role)
                .parts(newParts)
                .build());
        return truncated;
    }

    /** 把 response map 的字符串字段总长压到预算内（保留每字段 JSON 键开销；优先收缩最长字段） */
    private static void capResponseStrings(Map<String, Object> resp, int budget) {
        List<Map.Entry<String, Object>> strings = resp.entrySet().stream()
                .filter(e -> e.getValue() instanceof String)
                .toList();
        int overhead = (resp.size() - strings.size()) * 48 + strings.size() * 32;
        int usable = Math.max(budget - overhead, 200);
        int total = 0;
        for (Map.Entry<String, Object> e : strings) {
            total += ((String) e.getValue()).length();
        }
        for (int round = 0; round < 4 && total > usable; round++) {
            Map.Entry<String, Object> longest = null;
            int longestLen = 0;
            for (Map.Entry<String, Object> e : strings) {
                int len = ((String) e.getValue()).length();
                if (len > longestLen) {
                    longest = e;
                    longestLen = len;
                }
            }
            if (longest == null) {
                break;
            }
            int reduce = total - usable;
            String v = (String) longest.getValue();
            int newLen = Math.max(v.length() - reduce, Math.max(v.length() / 2, 200));
            String capped = v.length() <= newLen ? v
                    : TextRetentionUtil.retainHeadTail(v, newLen);
            total -= v.length() - capped.length();
            longest.setValue(capped);
        }
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

    /** 是否为压缩摘要事件（EventActions.compaction 标记，W4） */
    private static boolean isCompactionEvent(Event event) {
        return event.actions() != null && event.actions().compaction().isPresent();
    }

    /**
     * 追加压缩摘要事件并剪枝被覆盖区间（W4）：
     * 普通事件时间戳落在 [start,end] 内 → 移除（内容已折叠进摘要）；
     * 旧压缩事件完全被新摘要覆盖（start/end 均在内）→ 移除（滚动折叠链，内容已并入新摘要）。
     * 剪枝后 Contents 处理器按剩余压缩标记重建请求内容，语义与框架滚动摘要一致。
     */
    private void appendCompactionEvent(SessionSnapshot snapshot, Event compactionEvent) {
        List<Event> events = snapshot.getRawEvents();
        var compaction = compactionEvent.actions().compaction().orElse(null);
        if (compaction == null) {
            return;
        }
        long start = compaction.startTimestamp();
        long end = compaction.endTimestamp();
        int before = events.size();
        events.removeIf(event -> {
            if (isCompactionEvent(event)) {
                var ec = event.actions().compaction().orElse(null);
                return ec != null && ec.startTimestamp() >= start && ec.endTimestamp() <= end;
            }
            return event.timestamp() >= start && event.timestamp() <= end;
        });
        events.add(compactionEvent);
        log.debug("压缩摘要事件已追加，剪枝 {} 条覆盖事件 [{}..{}]，剩余 {} 条",
                before - events.size() + 1, start, end, events.size());
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

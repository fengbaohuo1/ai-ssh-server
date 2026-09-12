package jainingfei.site.ai.agent.test.domain.agent.session;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.SessionStore;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话存储（SessionStore）单元测试——内存三级结构 + 读写原语，无网络无 DB 无 Spring。
 *
 * <p>覆盖：快照创建（sessionId 缺省生成 UUID）、快照 ↔ ADK Session 视图转换、
 * GetSessionConfig 事件过滤、事件规范化（补 id/时间戳）、stateDelta 合并、删除与更新时刻。
 */
public class SessionStoreTest {

    private final SessionStore store = new SessionStore();

    private static Event event(String author, String role, String text, long timestamp) {
        return Event.builder()
                .invocationId("inv-1")
                .author(author)
                .timestamp(timestamp)
                .content(Content.builder().role(role).parts(List.of(Part.fromText(text))).build())
                .build();
    }

    @Test
    @DisplayName("createSnapshot：显式 sessionId 登记进三级结构，可被 findSnapshot 找回")
    public void test_create_and_find_snapshot() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());

        assertEquals("app", snapshot.getSessionKey().appName());
        assertEquals("u1", snapshot.getSessionKey().userId());
        assertEquals("s1", snapshot.getSessionKey().id());
        assertNotNull(snapshot.getLastUpdateTime());
        assertTrue(snapshot.getRawEvents().isEmpty());
        assertSame(snapshot, store.findSnapshot("app", "u1", "s1"));
    }

    @Test
    @DisplayName("createSnapshot：sessionId 为 null/空白时生成 UUID，且可被找回")
    public void test_create_snapshot_generates_uuid() {
        SessionSnapshot fromNull = store.createSnapshot("app", "u1", null, new ConcurrentHashMap<>());
        SessionSnapshot fromBlank = store.createSnapshot("app", "u1", "   ", new ConcurrentHashMap<>());

        assertNotNull(fromNull.getSessionKey().id());
        assertFalse(fromNull.getSessionKey().id().isBlank());
        assertNotNull(fromBlank.getSessionKey().id());
        assertSame(fromNull, store.findSnapshot("app", "u1", fromNull.getSessionKey().id()));
    }

    @Test
    @DisplayName("findSnapshot：未知 app / user / session 一律返回 null（不抛异常）")
    public void test_find_snapshot_missing() {
        assertNull(store.findSnapshot("nope", "u1", "s1"));
        store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        assertNull(store.findSnapshot("app", "u2", "s1"));
        assertNull(store.findSnapshot("app", "u1", "s2"));
    }

    @Test
    @DisplayName("createSnapshot：初始 state 被包进快照，null 时给空 state")
    public void test_create_snapshot_state() {
        ConcurrentHashMap<String, Object> initial = new ConcurrentHashMap<>(Map.of("k", "v"));
        SessionSnapshot withState = store.createSnapshot("app", "u1", "s1", initial);
        assertEquals("v", withState.getState().get("k"));

        SessionSnapshot nullState = store.createSnapshot("app", "u1", "s2", null);
        assertNotNull(nullState.getState());
        assertTrue(nullState.getState().isEmpty());
    }

    @Test
    @DisplayName("toSession：快照 → ADK Session，无覆盖时带上全量事件与 state")
    public void test_to_session_uses_snapshot_events() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        snapshot.getRawEvents().add(event("user", "user", "你好", 1000L));

        Session session = store.toSession(snapshot, Optional.empty());

        assertEquals("s1", session.id());
        assertEquals("app", session.appName());
        assertEquals("u1", session.userId());
        assertEquals(1, session.events().size());
        assertEquals(snapshot.getLastUpdateTime(), session.lastUpdateTime());
    }

    @Test
    @DisplayName("toSession：eventsOverride 生效（压缩后只回传近端子集）")
    public void test_to_session_with_override() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        snapshot.getRawEvents().add(event("user", "user", "旧", 1000L));
        snapshot.getRawEvents().add(event("model", "model", "新", 2000L));

        Event only = event("model", "model", "只有这条", 3000L);
        Session session = store.toSession(snapshot, Optional.of(List.of(only)));

        assertEquals(1, session.events().size());
        assertSame(only, session.events().get(0));
    }

    @Test
    @DisplayName("listSessions：列出某用户在某应用下的全部会话；deleteSession 后消失")
    public void test_list_and_delete_sessions() {
        store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        store.createSnapshot("app", "u1", "s2", new ConcurrentHashMap<>());
        store.createSnapshot("app", "u2", "s3", new ConcurrentHashMap<>());

        assertEquals(2, store.listSessions("app", "u1").size());
        assertEquals(1, store.listSessions("app", "u2").size());
        assertTrue(store.listSessions("app", "u9").isEmpty());

        store.deleteSession("app", "u1", "s1");
        assertEquals(1, store.listSessions("app", "u1").size());
        assertNull(store.findSnapshot("app", "u1", "s1"));
    }

    @Test
    @DisplayName("filterEvents：numRecentEvents 只保留最近 N 条；超过总数时全保留")
    public void test_filter_events_recent_count() {
        List<Event> events = new ArrayList<>(List.of(
                event("user", "user", "e1", 1000L),
                event("model", "model", "e2", 2000L),
                event("user", "user", "e3", 3000L)));

        List<Event> recent = store.filterEvents(events, GetSessionConfig.builder().numRecentEvents(2).build());
        assertEquals(2, recent.size());
        assertEquals(3000L, recent.get(1).timestamp());

        List<Event> all = store.filterEvents(events, GetSessionConfig.builder().numRecentEvents(99).build());
        assertEquals(3, all.size());
        assertEquals(3, events.size(), "过滤不得修改原列表");
    }

    @Test
    @DisplayName("filterEvents：afterTimestamp 过滤掉不晚于该时刻的事件，且可与条数组合")
    public void test_filter_events_after_timestamp() {
        List<Event> events = new ArrayList<>(List.of(
                event("user", "user", "e1", 1000L),
                event("model", "model", "e2", 2000L),
                event("user", "user", "e3", 3000L)));

        List<Event> after = store.filterEvents(events,
                GetSessionConfig.builder().afterTimestamp(Instant.ofEpochMilli(2000L)).build());
        assertEquals(1, after.size());
        assertEquals(3000L, after.get(0).timestamp(), "边界时刻本身应被排除");

        List<Event> combined = store.filterEvents(events, GetSessionConfig.builder()
                .afterTimestamp(Instant.ofEpochMilli(1000L)).numRecentEvents(1).build());
        assertEquals(1, combined.size());
        assertEquals(3000L, combined.get(0).timestamp());
    }

    @Test
    @DisplayName("normalizeEvent：缺时间戳补当前时间、缺 id 补生成 id；已有值不覆盖")
    public void test_normalize_event() {
        long before = System.currentTimeMillis();
        Event bare = store.normalizeEvent(Event.builder().invocationId("inv").author("user").build());

        assertTrue(bare.timestamp() >= before);
        assertNotNull(bare.id());
        assertFalse(bare.id().isBlank());

        Event explicit = Event.builder().id("fixed-id").invocationId("inv").author("user")
                .timestamp(123L).build();
        Event normalized = store.normalizeEvent(explicit);
        assertEquals("fixed-id", normalized.id());
        assertEquals(123L, normalized.timestamp());
    }

    @Test
    @DisplayName("mergeStateDelta：写入新键、State.REMOVED 删除键、空 delta 不动 state")
    public void test_merge_state_delta() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        snapshot.getState().put("old", "v");

        store.mergeStateDelta(snapshot, Event.builder().invocationId("inv").author("user")
                .actions(EventActions.builder().stateDelta(Map.of("new", "n", "old", State.REMOVED)).build())
                .build());

        assertEquals("n", snapshot.getState().get("new"));
        assertFalse(snapshot.getState().containsKey("old"), "REMOVED 哨兵值表示删除该键");

        store.mergeStateDelta(snapshot, Event.builder().invocationId("inv").author("user").build());
        assertEquals("n", snapshot.getState().get("new"), "无 actions 的事件不得影响 state");

        store.mergeStateDelta(snapshot, Event.builder().invocationId("inv").author("user")
                .actions(EventActions.builder().stateDelta(Map.of()).build()).build());
        assertEquals("n", snapshot.getState().get("new"));
    }

    @Test
    @DisplayName("resolveUpdateTime：有时间戳用时间戳，无时间戳回退当前时刻")
    public void test_resolve_update_time() {
        assertEquals(Instant.ofEpochMilli(5000L), store.resolveUpdateTime(
                Event.builder().invocationId("inv").author("user").timestamp(5000L).build()));

        long before = System.currentTimeMillis();
        Instant fallback = store.resolveUpdateTime(Event.builder().invocationId("inv").author("user").build());
        assertTrue(fallback.toEpochMilli() >= before);
    }

    @Test
    @DisplayName("SessionKey 三元组作为会话标识：同 id 不同 user 互不覆盖")
    public void test_snapshot_isolation_by_key() {
        SessionSnapshot first = store.createSnapshot("app", "u1", "same-id", new ConcurrentHashMap<>());
        SessionSnapshot second = store.createSnapshot("app", "u2", "same-id", new ConcurrentHashMap<>());

        assertFalse(first == second);
        assertSame(first, store.findSnapshot("app", "u1", "same-id"));
        assertSame(second, store.findSnapshot("app", "u2", "same-id"));
        assertEquals(new SessionKey("app", "u1", "same-id"), first.getSessionKey());
    }
}

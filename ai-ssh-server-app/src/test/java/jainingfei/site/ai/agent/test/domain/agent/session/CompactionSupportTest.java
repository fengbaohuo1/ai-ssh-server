package jainingfei.site.ai.agent.test.domain.agent.session;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CompactionSupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.EventGovernor;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.SessionStore;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩与裁剪（CompactionSupport）单元测试——ADK 事件压缩的折叠/剪枝与兜底 trim，无网络无 Spring。
 *
 * <p>两条互斥策略：
 * <pre>
 *   压缩开启  追加摘要事件并剪掉被覆盖区间（滚动折叠链，内存有界）
 *   压缩关闭  按"完整最近几轮"回收 + 总条数硬上限兜底
 * </pre>
 */
public class CompactionSupportTest {

    private SessionGovernanceProperties properties;
    private CompactionSupport compaction;
    private final SessionStore store = new SessionStore();

    @BeforeEach
    public void setUp() {
        properties = new SessionGovernanceProperties();
        properties.setMaxTurns(2);
        properties.setMaxEvents(10);
        properties.setMaxUserText(200);
        properties.setMaxToolText(500);
        properties.setMaxAssistantText(500);
        compaction = new CompactionSupport(properties, new EventGovernor(properties));
    }

    // ===== 构造事件 =====

    private static Event event(String role, String text, long timestamp) {
        return Event.builder()
                .invocationId("inv-1")
                .author(role)
                .timestamp(timestamp)
                .content(Content.builder().role(role).parts(List.of(Part.fromText(text))).build())
                .build();
    }

    private static Event compactionEvent(long start, long end) {
        Content summary = Content.builder().role("model")
                .parts(List.of(Part.fromText("[历史摘要] " + start + ".." + end))).build();
        return Event.builder()
                .invocationId("inv-1")
                .author("system")
                .timestamp(end)
                .content(summary)
                .actions(EventActions.builder()
                        .compaction(EventCompaction.builder()
                                .startTimestamp(start)
                                .endTimestamp(end)
                                .compactedContent(summary)
                                .build())
                        .build())
                .build();
    }

    private static List<Long> timestamps(List<Event> events) {
        return events.stream().map(Event::timestamp).toList();
    }

    // ===== isCompactionEvent =====

    @Test
    @DisplayName("isCompactionEvent：只有带 compaction 标记的事件才是压缩摘要事件")
    public void test_is_compaction_event() {
        assertTrue(CompactionSupport.isCompactionEvent(compactionEvent(1000, 2000)));
        assertFalse(CompactionSupport.isCompactionEvent(event("user", "普通事件", 1000)));
    }

    // ===== appendCompactionEvent =====

    @Test
    @DisplayName("appendCompactionEvent：剪掉时间戳落在 [start,end] 内的事件，追加摘要事件到尾部")
    public void test_append_prunes_covered_range() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        snapshot.getRawEvents().addAll(List.of(
                event("user", "e1", 1000),
                event("model", "e2", 2000),
                event("user", "e3", 3000),
                event("model", "e4", 4000)));

        Event summary = compactionEvent(1500, 3500);
        compaction.appendCompactionEvent(snapshot, summary);

        List<Event> events = snapshot.getRawEvents();
        assertEquals(3, events.size(), "覆盖区间内的两条被摘要取代");
        assertEquals(List.of(1000L, 4000L, 3500L), timestamps(events));
        assertSame(summary, events.get(2), "摘要事件追加在尾部");
    }

    @Test
    @DisplayName("appendCompactionEvent：边界时刻本身也算被覆盖（含端点）")
    public void test_append_range_is_inclusive() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        snapshot.getRawEvents().addAll(List.of(
                event("user", "left", 1000),
                event("user", "right", 4000)));

        compaction.appendCompactionEvent(snapshot, compactionEvent(1000, 4000));

        assertEquals(1, snapshot.getRawEvents().size(), "端点事件应被折叠进摘要");
        assertTrue(CompactionSupport.isCompactionEvent(snapshot.getRawEvents().get(0)));
    }

    @Test
    @DisplayName("appendCompactionEvent：完全被新摘要覆盖的旧摘要事件被移除（滚动折叠链）")
    public void test_append_removes_fully_covered_old_compaction() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        Event oldSummary = compactionEvent(2000, 3000);
        Event outside = event("user", "区间外的旧事件", 500);
        snapshot.getRawEvents().addAll(List.of(outside, oldSummary));

        Event newSummary = compactionEvent(1000, 4000);
        compaction.appendCompactionEvent(snapshot, newSummary);

        assertEquals(List.of(500L, 4000L), timestamps(snapshot.getRawEvents()),
                "旧摘要内容已并入新摘要必须移除；区间外的事件保留");
        assertSame(newSummary, snapshot.getRawEvents().get(1));
    }

    @Test
    @DisplayName("appendCompactionEvent：未被完全覆盖的旧摘要事件保留（区间只部分重叠）")
    public void test_append_keeps_partially_overlapping_old_compaction() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        Event oldSummary = compactionEvent(500, 2000);
        snapshot.getRawEvents().add(oldSummary);

        compaction.appendCompactionEvent(snapshot, compactionEvent(1500, 3000));

        assertEquals(2, snapshot.getRawEvents().size(), "部分重叠的旧摘要不得丢失");
        assertSame(oldSummary, snapshot.getRawEvents().get(0));
    }

    @Test
    @DisplayName("appendCompactionEvent：事件不带 compaction 标记时直接返回，不改动列表")
    public void test_append_without_compaction_marker_is_noop() {
        SessionSnapshot snapshot = store.createSnapshot("app", "u1", "s1", new ConcurrentHashMap<>());
        Event plain = event("user", "普通事件", 1000);
        snapshot.getRawEvents().add(plain);

        compaction.appendCompactionEvent(snapshot, event("system", "没有 compaction 标记", 2000));

        assertEquals(1, snapshot.getRawEvents().size());
        assertSame(plain, snapshot.getRawEvents().get(0));
    }

    // ===== trim =====

    @Test
    @DisplayName("trim：按用户轮次回收，保留完整的最近 maxTurns 轮")
    public void test_trim_by_turns() {
        List<Event> events = new ArrayList<>(List.of(
                event("user", "u1", 1000), event("model", "a1", 1100),
                event("user", "u2", 2000), event("model", "a2", 2100),
                event("user", "u3", 3000), event("model", "a3", 3100)));

        compaction.trim(events);

        assertEquals(4, events.size(), "从尾部数满 2 个 user 轮次即停");
        assertEquals(2000L, events.get(0).timestamp(), "保留的是 u2 起始的完整两轮");
        assertEquals(3100L, events.get(3).timestamp());
    }

    @Test
    @DisplayName("trim：工具结果事件（role=user 但带 FunctionResponse）不会被误计成用户轮次")
    public void test_trim_ignores_tool_result_as_user_turn() {
        Event toolResult = Event.builder()
                .invocationId("inv-1").author("sshOperator").timestamp(1500)
                .content(Content.builder().role("user")
                        .parts(List.of(Part.builder().functionResponse(
                                com.google.genai.types.FunctionResponse.builder()
                                        .id("c1").name("executeCommand")
                                        .response(java.util.Map.of("result", "ok")).build()).build()))
                        .build())
                .build();

        List<Event> events = new ArrayList<>(List.of(
                event("user", "u1", 1000), toolResult, event("model", "a1", 1600),
                event("user", "u2", 2000), event("model", "a2", 2100),
                event("user", "u3", 3000), event("model", "a3", 3100)));

        compaction.trim(events);

        assertEquals(4, events.size(), "工具结果不应消耗轮次预算");
        assertEquals(2000L, events.get(0).timestamp());
    }

    @Test
    @DisplayName("trim：轮次回收后仍超总条数上限时再按 maxEvents 硬裁头部")
    public void test_trim_by_max_events() {
        properties.setMaxEvents(3);
        List<Event> events = new ArrayList<>(List.of(
                event("model", "m1", 1000), event("model", "m2", 2000), event("model", "m3", 3000),
                event("model", "m4", 4000), event("model", "m5", 5000)));

        compaction.trim(events);

        assertEquals(3, events.size());
        assertEquals(List.of(3000L, 4000L, 5000L), timestamps(events), "硬裁只丢头部，保留最近事件");
    }

    @Test
    @DisplayName("trim：空列表与不超限列表均为空操作")
    public void test_trim_noop() {
        List<Event> empty = new ArrayList<>();
        compaction.trim(empty);
        assertTrue(empty.isEmpty());

        List<Event> small = new ArrayList<>(List.of(event("user", "u1", 1000), event("model", "a1", 1100)));
        compaction.trim(small);
        assertEquals(2, small.size());
    }
}

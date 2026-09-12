package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.model.SessionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

/**
 * 压缩与裁剪——ADK 事件压缩（W4）的落地，以及压缩关闭时的旧丢头 trim。
 *
 * <p>两部分：
 * <pre>
 *   压缩摘要事件：追加并剪枝被覆盖区间（普通事件按时间戳落在 [start,end] 内；旧压缩事件
 *                 被新摘要完全覆盖）——滚动折叠链，内存有界，压缩事件本身永不被 trim 裁掉
 *   兜底 trim：先按用户轮次回收（保留"完整的最近几轮"），再按总条数上限硬裁
 * </pre>
 *
 * <p>二者互斥：压缩开启时长度治理交给压缩引擎，丢头 trim 会与"折叠回填"冲突
 * （把摘要前的原文或摘要本身裁掉），所以由调用方按 {@code compact.enabled} 决定是否 trim。
 */
@Slf4j
@Component
public class CompactionSupport {

    private final SessionGovernanceProperties properties;
    private final EventGovernor eventGovernor;

    public CompactionSupport(SessionGovernanceProperties properties, EventGovernor eventGovernor) {
        this.properties = properties;
        this.eventGovernor = eventGovernor;
    }

    /** 是否为压缩摘要事件（EventActions.compaction 标记） */
    public static boolean isCompactionEvent(Event event) {
        return event.actions() != null && event.actions().compaction().isPresent();
    }

    /**
     * 追加压缩摘要事件并剪枝被覆盖区间：
     * 普通事件时间戳落在 [start,end] 内 → 移除（内容已折叠进摘要）；
     * 旧压缩事件完全被新摘要覆盖（start/end 均在内）→ 移除（内容已并入新摘要）。
     * 剪枝后 Contents 处理器按剩余压缩标记重建请求内容，语义与框架滚动摘要一致。
     */
    public void appendCompactionEvent(SessionSnapshot snapshot, Event compactionEvent) {
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
    public void trim(List<Event> events) {
        trimByTurn(events);
        int maxEvents = properties.getMaxEvents();
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
        int maxTurns = properties.getMaxTurns();
        LinkedList<Event> trimmed = new LinkedList<>();
        int userTurnCount = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event current = events.get(i);
            trimmed.addFirst(current);
            if ("user".equalsIgnoreCase(eventGovernor.resolveRole(current))) {
                userTurnCount++;
                if (userTurnCount >= maxTurns) {
                    break;
                }
            }
        }
        events.clear();
        events.addAll(trimmed);
    }
}

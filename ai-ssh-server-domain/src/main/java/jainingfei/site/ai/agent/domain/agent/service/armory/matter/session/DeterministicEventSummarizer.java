package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.summarizer.BaseEventSummarizer;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性事件摘要器（W4/P4.1）——ADK 1.9 事件压缩引擎的可插拔 {@link BaseEventSummarizer}。
 *
 * <p>与 {@code LlmEventSummarizer}（LLM 摘要，额外一次模型请求）不同，本实现<b>零额外请求</b>：
 * 把被折叠区间的事件文本做结构化浓缩（角色 + 可见正文/工具调用/工具结果，逐条 head/tail 截断），
 * 输出单条 user 角色摘要事件（首行 {@code [历史摘要]}，文本预算 {@code summary-max-chars}）。
 * 上一条滚动摘要以事件内容形式进入输入列表，自然并入新摘要（滚动折叠链）。
 *
 * <p>产物形状对齐框架约定：{@link EventCompaction}(start/end) + {@code compactedContent}，
 * Contents 处理器按时间戳把被覆盖区间替换为摘要内容（"折叠回填"），摘要文本不含隐藏草稿标记。
 */
@Slf4j
@Component
public class DeterministicEventSummarizer implements BaseEventSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY_HEADER = "[历史摘要]";

    /** 单条可见文本参与摘要的最大字符数（head/tail 保留，省略计数） */
    private static final int PART_MAX_CHARS = 200;

    /** 单条工具参数/结果参与摘要的最大字符数 */
    private static final int PART_TOOL_MAX_CHARS = 240;

    /** 摘要文本预算（ai.session-governance.compact.summary-max-chars） */
    @Resource
    private SessionGovernanceProperties governance;

    /** 摘要文本预算（字符） */
    private int summaryMaxChars() {
        return governance.getCompact().getSummaryMaxChars();
    }

    @Override
    public Maybe<Event> summarizeEvents(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Maybe.empty();
        }

        long start = events.get(0).timestamp();
        long end = events.get(events.size() - 1).timestamp();

        StringBuilder sb = new StringBuilder(SUMMARY_HEADER).append("\n");
        for (Event event : events) {
            appendEventLine(sb, event);
            if (sb.length() > summaryMaxChars()) {
                break;
            }
        }
        // 预算硬截断（尾部省略标记）
        if (sb.length() > summaryMaxChars()) {
            sb.setLength(summaryMaxChars());
            sb.append("\n…(摘要已截断)");
        }
        String text = sb.toString();

        Content content = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(text)))
                .build();
        EventCompaction compaction = EventCompaction.builder()
                .startTimestamp(start)
                .endTimestamp(end)
                .compactedContent(content)
                .build();
        Event summaryEvent = Event.builder()
                .id(Event.generateEventId())
                .author("user")
                .invocationId(Event.generateEventId())
                .actions(EventActions.builder().compaction(compaction).build())
                .build();
        log.info("事件压缩产出摘要事件: 覆盖 {} 条事件 [{} .. {}]，摘要长度 {}",
                events.size(), start, end, text.length());
        return Maybe.just(summaryEvent);
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    /** 单条事件 → 一行摘要（角色标签 + 可见正文；无正文时呈现工具调用/结果） */
    private void appendEventLine(StringBuilder sb, Event event) {
        Optional<Content> contentOpt = event.content();
        if (contentOpt.isEmpty()) {
            return;
        }
        Content content = contentOpt.get();
        String roleLabel = roleLabel(content.role().orElse(event.author()));

        List<Part> parts = content.parts().orElse(List.of());
        StringBuilder text = new StringBuilder();
        for (Part part : parts) {
            part.text().ifPresent(text::append);
        }
        String visible = ReasoningTagUtil.strip(text.toString());
        if (!visible.isBlank()) {
            appendLine(sb, roleLabel + ": " + cap(visible.trim(), PART_MAX_CHARS));
            return;
        }
        for (Part part : parts) {
            if (part.functionCall().isPresent()) {
                var fc = part.functionCall().get();
                appendLine(sb, "[工具调用] " + fc.name().orElse("unknown")
                        + "(" + cap(toJson(fc.args().orElse(Map.of())), PART_TOOL_MAX_CHARS) + ")");
            } else if (part.functionResponse().isPresent()) {
                var fr = part.functionResponse().get();
                appendLine(sb, "[工具结果] " + fr.name().orElse("unknown")
                        + " → " + cap(toJson(fr.response().orElse(Map.of())), PART_TOOL_MAX_CHARS));
            }
        }
    }

    private void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (sb.length() + line.length() + 2 > summaryMaxChars()) {
            return;
        }
        sb.append("- ").append(line).append("\n");
    }

    private static String roleLabel(String role) {
        if (role == null) {
            return "";
        }
        return switch (role.toLowerCase()) {
            case "user" -> "用户";
            case "model", "assistant" -> "助手";
            case "tool" -> "工具";
            default -> role;
        };
    }

    /** 文本预算截断：head/tail 保留 + 精确省略计数（正文中段对摘要价值最低） */
    private static String cap(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}

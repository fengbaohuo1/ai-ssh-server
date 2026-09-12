package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件治理器——按事件角色做净化与限长（框架层会话治理的"策略"部分）。
 *
 * <p>三条策略（上限来自 {@link SessionGovernanceProperties}）：
 * <pre>
 *   user      → 剥离动态消息后缀（2-9 起动态段在分隔线之后），只留原始用户问题（+工具结果），并截断
 *   tool      → 结构化截断：FunctionResponse 的 part 结构（id/name/response）必须保留，
 *               只对 response map 里的字符串字段做 head/tail 限长
 *   assistant → marker 感知限长：隐藏草稿标记区不参与截断；带 FunctionCall 的事件只限长文本部分
 * </pre>
 *
 * <p>本类只做"输入事件 → 治理后事件"，不碰存储与裁剪（那是 {@link SessionStore} 与
 * {@link CompactionSupport} 的职责），因此全部方法都可单测。
 */
@Slf4j
@Component
public class EventGovernor {

    private final SessionGovernanceProperties properties;

    public EventGovernor(SessionGovernanceProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析事件角色：<b>先看结构化 part，再看 content.role，最后回退 author</b>。
     *
     * <p>为什么不能只看 content.role：ADK 的 {@code Functions.buildResponseEvent} 把工具结果事件的
     * content.role 设成 <b>"user"</b>（Gemini 风格：函数结果算作 user 轮内容）。只按 role 判断会把
     * 工具结果当"用户消息"送进 {@link #governUserEvent}——一旦其 stringify 文本超过 user 上限，
     * 就会被重建成<b>纯文本 user 事件</b>，FunctionResponse part 随之丢失，
     * 下一轮历史重建就凑不出 tool 消息与 assistant tool_calls 的配对，provider 直接 400：
     * <pre>
     *   "An assistant message with 'tool_calls' must be followed by tool messages
     *    responding to each 'tool_call_id'."
     * </pre>
     * 因此结构化优先：带 FunctionResponse 的事件一律是工具结果，带 FunctionCall 的按助手事件处理
     * （只影响限长方式，不影响角色语义）。
     */
    public String resolveRole(Event event) {
        List<Part> parts = event.content().flatMap(Content::parts).orElse(List.of());
        if (parts.stream().anyMatch(p -> p.functionResponse().isPresent())) {
            return "tool";
        }
        if (parts.stream().anyMatch(p -> p.functionCall().isPresent())) {
            return "model";
        }
        return event.content().flatMap(Content::role).orElse(event.author());
    }

    /** assistant/model 均视为助手消息角色 */
    public static boolean isAssistantRole(String role) {
        return "assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role);
    }

    /**
     * user 事件净化：剥离动态消息后缀，只保留原始用户问题（及内嵌工具结果），并按上限截断。
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
     *
     *   输出：
     *   请继续查看 error.log
     *   [上一轮工具执行结果]
     *   $ tail -n 50 error.log
     *   ...
     * </pre>
     */
    public Event governUserEvent(Event event) {
        String text = event.stringifyContent();
        if (text == null || text.isBlank()) {
            return event;
        }

        String actualUserMessage = stripDynamicSuffix(text);
        int maxUserText = properties.getMaxUserText();
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
     * tool 事件结构化截断：FunctionResponse 事件必须保留 part 结构（id/name/response map）
     * ——历史重建按 part 回放成 spring-ai ToolResponseMessage 与 assistant tool_calls 配对；
     * stringify 成文本会把 JSON 截断成非法内容并丢失配对。
     */
    public Event governToolEvent(Event event) {
        int maxToolText = properties.getMaxToolText();
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
                Map<String, Object> response = new java.util.LinkedHashMap<>(fr.response().orElse(Map.of()));
                capResponseStrings(response, maxToolText);
                newParts.add(Part.builder()
                        .functionResponse(fr.toBuilder().response(response).build())
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

    /**
     * assistant 事件限长（结构化版本）：隐藏草稿标记区不参与 head/tail 截断，避免截断损坏
     * base64 标记导致下轮无法解析草稿；携带 FunctionCall parts 的事件只限长文本部分，
     * FunctionCall parts 原样保留——历史重建需要"完整工具调用结构 + 草稿标记"。
     */
    public Event governAssistantEvent(Event event) {
        int maxAssistantText = properties.getMaxAssistantText();
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

    // ═══════════════════════════════════════════════════════════════
    //  内部：文本级处理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从文本中剥离动态注入后缀。
     *
     * <p>按最后一个 {@code \n---\n} 分隔线切分并保留分隔线之前的部分——
     * 动态段自身不含分隔线，而用户原文/工具输出可能含分隔线，取最后一个最稳妥；
     * 且要求分隔线之后的内容以动态段典型标记开头（防御"动态段为空时工具输出自带
     * 分隔线"的误切）。若没有分隔线，再根据典型后缀段落标签做启发式判断。
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

    /** 文本版：草稿标记区原样保留，仅对可见正文做 head/tail 限长 */
    private String limitAssistantText(String text) {
        int maxAssistantText = properties.getMaxAssistantText();
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

    /** 把 response map 的字符串字段总长压到预算内（保留每字段 JSON 键开销；优先收缩最长字段） */
    private static void capResponseStrings(Map<String, Object> response, int budget) {
        List<Map.Entry<String, Object>> strings = response.entrySet().stream()
                .filter(e -> e.getValue() instanceof String)
                .toList();
        int overhead = (response.size() - strings.size()) * 48 + strings.size() * 32;
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
            String value = (String) longest.getValue();
            int newLen = Math.max(value.length() - reduce, Math.max(value.length() / 2, 200));
            String capped = value.length() <= newLen ? value
                    : TextRetentionUtil.retainHeadTail(value, newLen);
            total -= value.length() - capped.length();
            longest.setValue(capped);
        }
    }
}

package jainingfei.site.ai.agent.domain.agent.support;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ADK 事件 → 展示/落库内容提取工具（V3 托管循环的事件中继规则）。
 *
 * <p>FixedSpringAI 采用 partial/聚合语义后，一次 LLM 调用的文本先以 partial=true 分片事件
 * 流式到达，最后跟一条 partial 为空的聚合事件（携带全文 + 草稿标记 + FunctionCall parts）。
 * 会话/SSE 消费方若直接拼接事件文本会把整段回答重复一遍，因此统一用本类提取"可见文本增量"：
 * <ul>
 *   <li>分片事件（partial=true）→ 增量文本</li>
 *   <li>聚合事件（partial 为空）→ 文本与已累积分片相等时返回 null（去重）；
 *       不一致（如非流式单事件）则整体返回</li>
 * </ul>
 * 隐藏草稿标记（DeepSeek reasoning_content 落库载体）在提取时一律剥除——展示与同步聚合
 * 出口不泄漏 marker，仅 ADK 会话事件保留。
 */
public final class EventTextUtil {

    private EventTextUtil() {
    }

    /** 事件是否为流式分片（Runner 只转发不持久化的 partial 事件） */
    public static boolean isPartial(Event event) {
        return event.partial().orElse(false);
    }

    /**
     * 提取事件文本增量（剥草稿标记；空返回 null）。聚合事件与已累积文本重复时返回 null
     * （由累积器 {@code accumulated} 判定，调用方把非 null 增量 append 进它）。
     */
    public static String visibleTextDelta(Event event, StringBuilder accumulated) {
        String visible = visibleText(event);
        if (visible == null || visible.isEmpty()) {
            return null;
        }
        if (isPartial(event)) {
            accumulated.append(visible);
            return visible;
        }
        // 聚合事件（partial 为空）：与已流式分片文本一致则跳过（防重复渲染/落库）
        String current = accumulated.toString();
        if (visible.equals(current)) {
            return null;
        }
        // 不一致（例如非流式路径只有这一条事件）——整体补充，防止丢内容
        accumulated.setLength(0);
        accumulated.append(visible);
        return visible;
    }

    /** 事件可见文本（仅 text part，剥草稿标记；functionCall/functionResponse 不参与） */
    public static String visibleText(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> {
                    StringBuilder sb = new StringBuilder();
                    for (Part p : parts) {
                        p.text().ifPresent(sb::append);
                    }
                    return sb.toString();
                })
                .map(ReasoningTagUtil::strip)
                .orElse("");
    }

    /** 工具调用条目（id/name/args），供 SSE tool_call 步骤与统计 */
    public static List<ToolCallView> functionCalls(Event event) {
        List<ToolCallView> result = new ArrayList<>();
        event.content().flatMap(Content::parts).ifPresent(parts -> {
            for (Part part : parts) {
                part.functionCall().ifPresent(fc -> result.add(new ToolCallView(
                        fc.id().orElse(""),
                        fc.name().orElse("unknown"),
                        fc.args().orElse(Map.of()))));
            }
        });
        return result;
    }

    /** 工具结果条目（id/name/response map），供 SSE tool_result 步骤与工具副作用 */
    public static List<ToolResultView> functionResponses(Event event) {
        List<ToolResultView> result = new ArrayList<>();
        event.content().flatMap(Content::parts).ifPresent(parts -> {
            for (Part part : parts) {
                part.functionResponse().ifPresent(fr -> result.add(new ToolResultView(
                        fr.id().orElse(null),
                        fr.name().orElse("unknown"),
                        fr.response().orElse(Map.of()))));
            }
        });
        return result;
    }

    /** 工具调用视图（与 ADK FunctionCall 一一对应） */
    public record ToolCallView(String id, String name, Map<String, Object> args) {
    }

    /** 工具执行结果视图（与 ADK FunctionResponse 一一对应） */
    public record ToolResultView(String id, String name, Map<String, Object> response) {
    }

    /** 事件文本全文（字符串形式，供日志/调试） */
    public static String eventText(Event event) {
        return Optional.ofNullable(event.stringifyContent()).orElse("");
    }
}

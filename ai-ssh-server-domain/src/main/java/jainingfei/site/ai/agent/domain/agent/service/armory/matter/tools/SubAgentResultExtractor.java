package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.events.Event;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子智能体结果收敛器——把一次子会话的事件流压成"给父智能体看的那段结论"（教程 2-10 的结果收敛环节）。
 *
 * <p>为什么不能直接取"最后一个事件的文本"：子会话里存在<b>没有文本的事件</b>
 * （纯 functionCall、函数响应、仅 usage 的空流），末事件恰好是这些时，直接取文本会得到空串，
 * 父智能体就会收到一个"成功了但结果为空"的回答。
 *
 * <p>收敛规则（从后往前找）：
 * <ol>
 *   <li>跳过所有流式分片事件（partial=true）——它们只是同一段正文的碎片</li>
 *   <li>取最后一个<b>非空文本</b>的聚合事件（即最后一次 LLM 调用的完整回复）</li>
 *   <li>异常流（全是分片）：退回"最后一个有文本的事件"</li>
 *   <li>依然为空：返回空串，由调用方判定为失败（"empty sub agent result"）</li>
 * </ol>
 *
 * <p>长度治理：子智能体可能汇总了十几条命令的完整回显，直接塞回父上下文会挤掉后续推理空间。
 * 结果超过 {@link #MAX_RESULT_CHARS} 时按 head/tail 保留（尾部通常是结论/报错摘要），
 * 中段替换为带省略计数的标记，让父智能体知道被截断过。
 */
public final class SubAgentResultExtractor {

    /** 单次子智能体结果回灌父上下文的字符预算（约为叶子工具输出的 3 倍，兼顾汇总信息量） */
    public static final int MAX_RESULT_CHARS = 12_000;

    private SubAgentResultExtractor() {
    }

    /**
     * 提取子智能体最终结论文本（剥掉隐藏草稿标记，只保留可见正文，超限按 head/tail 保留）。
     *
     * @param events 子会话事件流（可能为 null/空）
     * @return 结论文本；无法提取时返回空串（永不返回 null）
     */
    public static String pickFinalText(List<Event> events) {
        return TextRetentionUtil.retainHeadTail(pickRawFinalText(events), MAX_RESULT_CHARS);
    }

    /** 未做长度裁剪的原始结论文本（供需要完整内容/测试的场景） */
    public static String pickRawFinalText(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        // ① 非分片的最后一个有文本事件
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (event == null || EventTextUtil.isPartial(event)) {
                continue;
            }
            String text = EventTextUtil.visibleText(event);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        // ② 异常流兜底：全是分片时，退回最后一个有文本的事件
        for (int i = events.size() - 1; i >= 0; i--) {
            String text = events.get(i) == null ? null : EventTextUtil.visibleText(events.get(i));
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    // ═══════════════════════════════════════════════════════════════
    //  执行痕迹（减少父智能体重复执行子任务）
    // ═══════════════════════════════════════════════════════════════

    /** 执行痕迹最多回灌的工具调用条数（再多也没有信息增量，只会挤占父上下文） */
    public static final int MAX_TRACE_CALLS = 15;

    /** 执行痕迹整体字符预算（结论正文另有 12k 预算，痕迹只做"补证据"用） */
    public static final int MAX_TRACE_CHARS = 1_500;

    /** 痕迹里单条命令/参数的字符上限 */
    public static final int MAX_TRACE_ITEM_CHARS = 120;

    /** 痕迹段标题（父提示词里会引用这句，改文案要同步改模板说明） */
    public static final String TRACE_HEADER = "[子智能体执行痕迹]";

    /**
     * 在子智能体结论后追加"它执行过哪些工具调用、结果如何"的紧凑清单。
     *
     * <p>背景（真实测试暴露）：父智能体只拿到子智能体的一段总结，无法判断哪些命令已经跑过，
     * 于是常常自己把同样的命令再跑一遍（重复执行、浪费回合与时间）。痕迹清单只列
     * 工具名 + 命令/参数摘要 + 成败，父智能体据此就能跳过重复调用。
     *
     * @param finalText 已收敛的结论文本（可为空）
     * @param events    子会话事件流
     * @return 结论 + 痕迹（整体仍受 {@link #MAX_RESULT_CHARS} head/tail 预算约束）
     */
    public static String appendCommandTrace(String finalText, List<Event> events) {
        String trace = commandTrace(events);
        if (trace.isEmpty()) {
            return finalText == null ? "" : finalText;
        }
        String text = finalText == null ? "" : finalText;
        String combined = text.isBlank() ? trace : text + "\n\n" + trace;
        // 痕迹放在尾部：head/tail 裁剪会保留尾部，结论中段被压缩时痕迹依然完整
        return TextRetentionUtil.retainHeadTail(combined, MAX_RESULT_CHARS + MAX_TRACE_CHARS);
    }

    /** 生成执行痕迹段（无工具调用时返回空串） */
    public static String commandTrace(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        List<EventTextUtil.ToolCallView> calls = new ArrayList<>();
        Map<String, EventTextUtil.ToolResultView> results = new LinkedHashMap<>();
        for (Event event : events) {
            if (event == null) {
                continue;
            }
            calls.addAll(EventTextUtil.functionCalls(event));
            for (EventTextUtil.ToolResultView result : EventTextUtil.functionResponses(event)) {
                results.put(result.id() == null ? "" : result.id(), result);
            }
        }
        if (calls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(TRACE_HEADER)
                .append("（父智能体可据此判断哪些命令已经执行过；已成功且结果够用的不要再重复执行）共 ")
                .append(calls.size()).append(" 条，列前 ").append(Math.min(calls.size(), MAX_TRACE_CALLS))
                .append(" 条：\n");
        int shown = 0;
        for (EventTextUtil.ToolCallView call : calls) {
            if (shown >= MAX_TRACE_CALLS) {
                break;
            }
            EventTextUtil.ToolResultView result = results.get(call.id() == null ? "" : call.id());
            sb.append("- ").append(call.name()).append(": ").append(summarizeArgs(call.args()));
            if (result == null) {
                sb.append(" → 未返回结果");
            } else if (Boolean.FALSE.equals(result.response().get("success"))) {
                sb.append(" → 失败");
                String error = firstNonBlank(
                        str(result.response().get("error")),
                        str(result.response().get("output")));
                if (!error.isBlank()) {
                    sb.append(": ").append(clip(error, 80));
                }
            } else {
                sb.append(" → 成功");
                Object exitStatus = result.response().get("exitStatus");
                if (exitStatus != null) {
                    sb.append("(exit=").append(exitStatus).append(')');
                }
            }
            sb.append('\n');
            shown++;
        }
        if (calls.size() > shown) {
            sb.append("（其余 ").append(calls.size() - shown).append(" 条已省略）\n");
        }
        String trace = sb.toString().trim();
        return trace.length() > MAX_TRACE_CHARS ? clip(trace, MAX_TRACE_CHARS) + " …（痕迹已截断）" : trace;
    }

    /** 工具参数摘要：优先 command 字段（SSH 工具都带它），否则回退整个参数 Map */
    private static String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "(无参数)";
        }
        Object command = args.get("command");
        String raw = command != null ? str(command) : str(args);
        return clip(raw.replace('\n', ' ').trim(), MAX_TRACE_ITEM_CHARS);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.replace('\n', ' ').trim();
            }
        }
        return "";
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}

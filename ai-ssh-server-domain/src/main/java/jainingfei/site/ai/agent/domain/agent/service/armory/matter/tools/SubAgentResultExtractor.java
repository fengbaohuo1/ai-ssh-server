package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.events.Event;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;

import java.util.List;

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
}

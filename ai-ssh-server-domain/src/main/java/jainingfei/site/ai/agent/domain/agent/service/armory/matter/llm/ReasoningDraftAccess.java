package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 推理内容的通道私有读写能力——由通道档案实现（不同通道的读法与携带方式不同）。
 *
 * <p>为什么需要它：推理内容的往返并不统一——原生通道用厂商专有的消息子类承载
 * （读要 {@code instanceof}、写要构造子类），OpenAI 协议通道则放在 {@link AssistantMessage}
 * 的元数据键上。把这层差异收在两个方法里，上层（{@link ReasoningDraftProtocol}）
 * 就只需要问"这条消息里有草稿吗""历史消息该怎么造"。
 *
 * <p>返回 {@code null} 或空串表示"本次没有"/"本通道不提供"（机会式捕获，不影响对话）。
 */
public interface ReasoningDraftAccess {

    /**
     * 从模型响应消息中读取推理内容分片。
     *
     * @param message 模型响应消息
     * @return 推理内容分片；无则 null
     */
    String readDraft(AssistantMessage message);

    /**
     * 构造历史 assistant 消息——把草稿放回<b>本通道认得</b>的载体（原生通道是专有消息子类，
     * 协议通道是元数据键）。
     *
     * @param visibleText 可见正文（允许空串：内容由 toolCalls 承载）
     * @param reasoning   推理内容，可为 null
     * @param toolCalls   工具调用，可为空
     * @return 历史消息；返回 {@code null} 表示本通道无专有载体，由协议层按通用元数据约定构造
     */
    default AssistantMessage buildHistoryMessage(String visibleText, String reasoning,
                                                 List<AssistantMessage.ToolCall> toolCalls) {
        return null;
    }
}

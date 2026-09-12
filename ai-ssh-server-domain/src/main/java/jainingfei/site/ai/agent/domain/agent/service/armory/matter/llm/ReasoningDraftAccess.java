package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 读取推理内容的能力接口——由通道档案实现（不同通道的读法不同）。
 *
 * <p>为什么需要它：推理内容的读法并不统一——原生通道用厂商专有的消息子类承载，
 * OpenAI 协议通道则放在 {@code AssistantMessage} 的元数据键上。把这层差异收在一个方法里，
 * 上层（{@link ReasoningDraftProtocol}）就只需要问"这条消息里有草稿吗"。
 *
 * <p>返回 {@code null} 或空串表示"本次没有"/"本通道不提供"，两者对上层等价（机会式捕获）。
 */
public interface ReasoningDraftAccess {

    /**
     * 从模型响应消息中读取推理内容分片。
     *
     * @param message 模型响应消息
     * @return 推理内容分片；无则 null
     */
    String readDraft(AssistantMessage message);
}

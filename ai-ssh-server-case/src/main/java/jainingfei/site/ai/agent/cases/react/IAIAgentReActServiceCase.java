package jainingfei.site.ai.agent.cases.react;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能体 ReAct 执行接口。
 *
 * <p>定义 Case 层对外的两种对话模式：
 * <ul>
 *   <li>{@link #chatStream(ChatRequestDTO)} — 流式对话（SSE），实时推送文本 + 工具事件</li>
 *   <li>{@link #chat(ChatRequestDTO)} — 同步对话，等待 ReAct 循环完成后返回最终结果</li>
 * </ul>
 *
 * <p>实现类通过节点链路完成 ReAct 编排，trigger 层只负责 HTTP 入参/出参。
 */
public interface IAIAgentReActServiceCase {

    /**
     * 流式对话（ReAct 模式，SSE）。
     *
     * @param requestDTO 对话请求
     * @return SSE 事件发射器
     */
    SseEmitter chatStream(ChatRequestDTO requestDTO);

    /**
     * 同步对话（ReAct 模式，阻塞等待完成）。
     *
     * @param requestDTO 对话请求
     * @return 最终响应文本
     */
    String chat(ChatRequestDTO requestDTO);

}

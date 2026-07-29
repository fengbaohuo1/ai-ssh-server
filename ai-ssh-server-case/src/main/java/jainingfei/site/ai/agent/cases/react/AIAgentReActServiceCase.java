package jainingfei.site.ai.agent.cases.react;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.cases.react.node.RootNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;

/**
 * AI 智能体 ReAct 执行服务实现。
 *
 * <p>作为 Case 层的"总装配入口"，负责：
 * <ul>
 *   <li>创建 SseEmitter + DynamicContext</li>
 *   <li>启动 RootNode 节点链路</li>
 *   <li>支持流式（SSE）和同步两种模式</li>
 * </ul>
 *
 * <p>节点链路：RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Override
    public SseEmitter chatStream(ChatRequestDTO requestDTO) {
        // 1. 创建 SSE 发射器（3 分钟超时）
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

        // 2. 启动心跳保活线程
        AbstractAIAgentReActSupport.startHeartbeat(emitter, requestDTO.getSessionId());

        // 3. 发送初始 thinking 事件
        try {
            emitter.send(SseEmitter.event().data("{\"v\":\"\",\"s\":{\"t\":\"thinking\"}}"));
        } catch (Exception ignored) {
        }

        try {
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getTerminalSessionId());

            // 4. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();

            // 5. 异步执行节点链路（避免阻塞 HTTP 线程）
            Thread streamThread = new Thread(() -> {
                try {
                    ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
                    log.info("ReAct 流式对话完成 - 步数:{} 工具调用:{} stopReason:{}",
                            result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
                } catch (Exception e) {
                    log.error("ReAct 流式对话异常", e);
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignored) {
                    }
                }
            }, "react-stream-" + requestDTO.getSessionId());
            streamThread.setDaemon(true);
            streamThread.start();

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 同步对话：阻塞等待 ReAct 循环完成，返回最终文本。
     * 用于测试和非流式场景。
     */
    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 同步对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try {
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new SseEmitter(60 * 1000L)) // 内部 emitter，不走 SSE
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            return result.getContent();

        } catch (Exception e) {
            log.error("ReAct 同步对话异常", e);
            return "Error: " + e.getMessage();
        }
    }

}

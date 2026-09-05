package jainingfei.site.ai.agent.cases.react;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.cases.react.node.RootNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能体 ReAct 执行服务实现（V3 薄壳：只负责 emitter/线程/入口）。
 *
 * <p>回合编排全部走节点链路（教学项目 2-3 的路径架构）：
 * <pre>
 * RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 * </pre>
 * 与显式循环版的区别：节点间<b>无循环回跳</b>——工具循环由 ADK Runner 在
 * AiCallNode 的一次 runAsync 内部推进（框架托管循环），节点链路是"回合流水线"，
 * 每个阶段一个节点、可独立插拔替换。
 *
 * <p>职责：
 * <ul>
 *   <li>流式对话（SSE）：创建 emitter + 心跳 + thinking 事件 → 节点链路</li>
 *   <li>同步对话：内部 emitter 走同一链路，返回最终可见文本</li>
 * </ul>
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

        // 2. 启动心跳保活线程（流结束自动停止）
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
                    log.info("ReAct 流式对话完成 - 工具调用:{} stopReason:{}",
                            result.getTotalToolCalls(), result.getStopReason());
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
     * 同步对话：阻塞等待回合完成，返回最终文本。
     * 用于测试和非流式场景。
     */
    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 同步对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try {
            // 内部 emitter：走与流式相同的节点链路（心跳不需要；事件只进内部缓冲）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new SseEmitter(60 * 1000L))
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            return result.getContent();
        } catch (Exception e) {
            log.error("ReAct 同步对话异常", e);
            return "Error: " + e.getMessage();
        }
    }

}

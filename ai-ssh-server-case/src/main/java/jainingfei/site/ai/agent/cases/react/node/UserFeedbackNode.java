package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ReAct 用户反馈节点 — 结果收口与资源清理。
 *
 * <p>职责：
 * <ol>
 *   <li>构建最终 ReActResultDTO</li>
 *   <li>发送 done SSE 事件</li>
 *   <li>调用 emitter.complete() 关闭流</li>
 *   <li>清理 ThreadLocal + ConcurrentHashMap 绑定</li>
 * </ol>
 *
 * <p>这是 ReAct 节点链路的终点。
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct UserFeedbackNode - 发送最终结果");

        SseEmitter emitter = ctx.getEmitter();

        try {
            // 1. 构建最终结果
            ReActResultDTO result = buildFinalResult(ctx);

            // 2. 发送 done SSE 事件
            String fullText = ctx.getAssistantContent() != null
                    ? ctx.getAssistantContent().toString() : "";
            sendDone(emitter, fullText);

            // 3. 关闭 emitter
            emitter.complete();

            log.info("ReAct 完成 - 步数: {} 工具调用: {} 停止原因: {}",
                    result.getTotalSteps(), result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode 发送失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            // 4. 清理上下文
            cleanup(ctx);
        }
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler; // 终点，不再路由
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext ctx) {
        String fullText = ctx.getAssistantContent() != null
                ? ctx.getAssistantContent().toString() : "";
        String stopReason = ctx.getStopReason();
        if (stopReason == null) stopReason = "completed";

        return ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(ctx.getStep())
                .totalToolCalls(ctx.getResult() != null
                        ? ctx.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(ctx.getCurrentToolCalls())
                .toolResults(ctx.getCurrentToolResults())
                .error(ctx.getErrorMessage())
                .build();
    }

    private void cleanup(DefaultReActFactory.DynamicContext ctx) {
        try {
            String sessionId = ctx.getSessionId();
            if (sessionId != null) {
                SshExecuteAdkTool.unbindSession(sessionId);
                unbindTerminalSession(sessionId);
            }
            clearCurrentTerminalSession();
            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }

}

package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import jainingfei.site.ai.agent.domain.agent.support.ReActStepSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ReAct 用户反馈节点 — 回合收口与资源清理（节点链路的终点）。
 *
 * <p>职责（V3）：
 * <ol>
 *   <li>助手文本逐条落库 + 结论记忆提取（2-8；AiCallNode 按"一次 LLM 调用一段"收集）</li>
 *   <li>发送 done SSE 事件并关闭 emitter（同步 chat 走内部 emitter，文本经 result 返回）</li>
 *   <li>组装最终 ReActResultDTO（content/统计/stopReason/error）</li>
 *   <li>清理：终端解绑、工具结果缓存清理</li>
 * </ol>
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct UserFeedbackNode - 回合收口");
        SseEmitter emitter = ctx.getEmitter();

        try {
            // 1. 助手文本逐条落库 + 结论记忆提取（一次 LLM 调用一条，按序批量）
            for (String utterance : ctx.getAssistantUtterances()) {
                try {
                    longTermMemoryService.saveAssistantMessage(ctx.getUserId(), ctx.getSessionId(), utterance);
                } catch (Exception e) {
                    log.warn("助手消息落库失败: {}", e.getMessage());
                }
            }

            // 2. 组装最终结果
            ReActResultDTO result = buildFinalResult(ctx);
            ctx.setResult(result);

            // 3. 收口三连：错误可见 → usage 统计 → done（带 stopReason）
            //    错误必须在 done 之前发：前端是拿 done 收尾的，先收尾再看错误就晚了
            String errorMessage = ctx.getErrorMessage();
            if (errorMessage != null && !errorMessage.isBlank()) {
                sendError(emitter, errorMessage);
            }
            if (ctx.hasUsage()) {
                sendUsage(emitter, ctx.getUsagePromptTokens(), ctx.getUsageCompletionTokens(),
                        ctx.getUsageTotalTokens(), ctx.getUsageCachedTokens());
            }
            sendDone(emitter, result.getStopReason());
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }

            log.info("ReAct 回合完成 - 工具调用: {} 文本长度: {} stopReason: {}",
                    result.getTotalToolCalls(), result.getContent() != null ? result.getContent().length() : 0,
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
        String stopReason = ctx.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }
        ReActResultDTO result = ctx.getResult();
        result.setContent(ctx.getVisibleText().toString());
        result.setStopReason(stopReason);
        result.setError(ctx.getErrorMessage());
        return result;
    }

    private void cleanup(DefaultReActFactory.DynamicContext ctx) {
        try {
            String sessionId = ctx.getSessionId();
            if (sessionId != null) {
                SshSessionBindings.unbindSession(sessionId);
                ReActStepSink.unbind(sessionId);
                unbindTerminalSession(sessionId);
                // 清理工具结果摘要等会话级上下文缓存，避免旧结果污染下一回合
                chatContextService.clearSessionContext(sessionId);
            }
            clearCurrentTerminalSession();
            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }

}

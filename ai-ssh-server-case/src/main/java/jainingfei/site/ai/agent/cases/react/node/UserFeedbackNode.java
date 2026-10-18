package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.SseSteps;
import jainingfei.site.ai.agent.cases.react.TurnToolSummaryCache;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jainingfei.site.ai.agent.domain.agent.support.ReActStepSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

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
            // 4. 收口前先把本轮工具执行摘要存到 case 层独立键（下一回合 AiCallNode 补进后缀），
            //    再清理 domain 会话缓存——顺序不能反，反了摘要会随缓存一起被清掉（[工具执行摘要] 恒空 bug）
            cacheTurnToolSummary(ctx);
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
                // 只解绑"本回合的步骤出口"：它绑的是本回合 emitter，下一回合 RootNode 会重新 bind，
                // 留着反而会把后续步骤事件写进已关闭的流
                ReActStepSink.unbind(sessionId);
                // 保留 SSH 会话绑定（SshSessionBindings / sessionTerminalMapping）：回合末解绑会让
                // RootNode 的"恢复分支"永远走不到（用户不重新选服务器就丢绑定）。
                // 连接关闭后的兜底解绑由 backend 侧统一处理，这里不重复实现。
                // 清理 domain 会话缓存（工具结果 Provider 等），避免旧结果污染下一回合；
                // 本轮摘要已在清理前存入 TurnToolSummaryCache，下一回合仍能注入 [工具执行摘要]。
                chatContextService.clearSessionContext(sessionId);
            }
            clearCurrentTerminalSession();
            SseSteps.release(ctx.getEmitter());
            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }

    /**
     * 收口前暂存"本轮工具执行摘要"（case 层独立键，供下一回合 AiCallNode 注入）。
     *
     * <p>domain 的 ToolResultProvider 缓存在回合末必清（见 {@link #cleanup} 注释），
     * 所以跨回合可读的"上一轮做了什么、成败如何"只能由本缓存承载。
     */
    private void cacheTurnToolSummary(DefaultReActFactory.DynamicContext ctx) {
        try {
            List<EventTextUtil.ToolResultView> results = ctx.getToolResultsCollected();
            if (results == null || results.isEmpty() || ctx.getSessionId() == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (EventTextUtil.ToolResultView result : results) {
                Map<String, Object> response = result.response();
                boolean flaggedSuccess = response != null && Boolean.TRUE.equals(response.get("success"));
                String output = firstNonBlank(
                        stringValue(response == null ? null : response.get("output")),
                        stringValue(response == null ? null : response.get("result")),
                        stringValue(response == null ? null : response.get("error")));
                // 成败口径与 ToolCallNode 一致：结构化 success + 失败信号文本二次判定
                boolean success = flaggedSuccess
                        && !longTermMemoryService.isFailureContent(output + "\n[success=" + flaggedSuccess + "]");
                sb.append(success ? "- [成功] " : "- [失败] ").append(result.name());
                String command = stringValue(response == null ? null : response.get("command"));
                if (!command.isBlank()) {
                    sb.append(": ").append(truncate(command, 80));
                }
                if (!output.isBlank()) {
                    sb.append(" → ").append(truncate(output.replace('\n', ' ').trim(), 160));
                }
                sb.append('\n');
            }
            TurnToolSummaryCache.put(ctx.getSessionId(), sb.toString().trim());
            log.debug("暂存本轮工具执行摘要 sessionId={} entries={}", ctx.getSessionId(), results.size());
        } catch (Exception e) {
            log.warn("暂存本轮工具执行摘要失败（不影响收口）: {}", e.getMessage());
        }
    }

    /** 对象 → 字符串（null 安全） */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 取第一个非空白字符串（与 ToolCallNode 提取口径一致：output / result / error） */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** 摘要行截断（防单条命令/输出过长） */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

}

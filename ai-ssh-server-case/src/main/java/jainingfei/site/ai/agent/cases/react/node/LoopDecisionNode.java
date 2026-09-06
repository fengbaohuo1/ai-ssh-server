package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct 循环决策节点 — V3 回合收尾决策（教学版 LoopDecisionNode 语义的映射 + V3.2 任务态收口）。
 *
 * <p>显式循环时代本节点决定"是否回跳 AiCallNode 进入下一轮"；V3 托管循环下
 * <b>循环推进与终止在 ADK Runner 内部</b>（finalResponse / maxLlmCalls），
 * 本节点做回合级收尾归纳：
 * <ol>
 *   <li>stopReason 兜底：未置位（正常完成）→ completed</li>
 *   <li>任务步进状态机收口（V3.2，写路径第三入口）：
 *       error → markTaskInterrupted（任务保持可续接 + 失败标记）；
 *       正常结束且有工具执行且无失败标记 → markTaskCompleted（任务达成归档）；
 *       无工具结果的正常回合 → 任务态原样保留（纯回答/追问确认，下回合同意图可复用续接）</li>
 *   <li>把统计/终止原因写回 ReActResultDTO</li>
 * </ol>
 * 路由单向到 UserFeedbackNode（无回跳）。
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Resource
    private IIntentService intentService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct LoopDecisionNode - 回合收尾决策，工具调用: {}", ctx.getToolCallCount());

        // 1. 兜底终止原因（错误在 AiCallNode 已置位 error）
        String stopReason = ctx.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }

        // 2. 任务步进状态机收口（回合级完成/中断判定）
        wrapUpTaskState(ctx, stopReason);

        // 3. 写回结果 DTO
        ctx.getResult().setStopReason(stopReason);
        if ("error".equals(stopReason)) {
            ctx.getResult().setError(ctx.getErrorMessage());
        }
        log.info("ReAct 回合收尾: stopReason={}", stopReason);

        // 4. 路由到收口节点
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // V3 无循环回跳：一律进入 UserFeedbackNode 收口
        return getBean("reactUserFeedbackNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  任务态收口（V3.2）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 回合收口判定任务态：error → 中断可续；正常 + 有工具结果 + 无失败 → 完成归档；
     * 其余（无工具结果的正常回合）保留原状，等用户下一条消息决定续接或开新任务。
     * <p>
     * 失败标记已在 ToolCallNode 逐工具结果打点：lastFeedbackFailed=false 表示本回合
     * 最后一个工具动作成功（回合内偶发失败后模型自行恢复成功也视为达成）。
     */
    private void wrapUpTaskState(DefaultReActFactory.DynamicContext ctx, String stopReason) {
        try {
            String sessionId = ctx.getSessionId();
            if (sessionId == null) {
                return;
            }
            TaskStateVO taskState = intentService.getTaskState(sessionId);
            if (taskState == null) {
                return;
            }
            boolean hasToolResults = ctx.getToolResultsCollected() != null
                    && !ctx.getToolResultsCollected().isEmpty();

            if ("error".equals(stopReason)) {
                intentService.markTaskInterrupted(sessionId);
                log.info("回合异常中断，任务态保持可续接 rootIntent={}", taskState.getRootIntent());
                return;
            }
            if (hasToolResults && !taskState.isLastFeedbackFailed()) {
                intentService.markTaskCompleted(sessionId);
                log.info("回合正常达成，任务态归档完成 rootIntent={} steps={}",
                        taskState.getRootIntent(),
                        taskState.getSteps() != null ? taskState.getSteps().size() : 0);
            }
            // 无工具结果的正常回合：任务态原样保留
        } catch (Exception e) {
            log.warn("任务态回合收口失败，跳过（不影响收尾）: {}", e.getMessage());
        }
    }

}

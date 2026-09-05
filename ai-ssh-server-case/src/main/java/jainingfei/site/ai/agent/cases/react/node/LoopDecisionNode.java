package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct 循环决策节点 — V3 回合收尾决策（教学版 LoopDecisionNode 语义的映射）。
 *
 * <p>显式循环时代本节点决定"是否回跳 AiCallNode 进入下一轮"；V3 托管循环下
 * <b>循环推进与终止在 ADK Runner 内部</b>（finalResponse / maxLlmCalls），
 * 本节点只做回合级收尾归纳：
 * <ol>
 *   <li>stopReason 兜底：未置位（正常完成）→ completed</li>
 *   <li>把统计/终止原因写回 ReActResultDTO</li>
 * </ol>
 * 路由单向到 UserFeedbackNode（无回跳）。
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct LoopDecisionNode - 回合收尾决策，工具调用: {}", ctx.getToolCallCount());

        // 1. 兜底终止原因（错误在 AiCallNode 已置位 error）
        String stopReason = ctx.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }

        // 2. 写回结果 DTO
        ctx.getResult().setStopReason(stopReason);
        if ("error".equals(stopReason)) {
            ctx.getResult().setError(ctx.getErrorMessage());
        }
        log.info("ReAct 回合收尾: stopReason={}", stopReason);

        // 3. 路由到收口节点
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // V3 无循环回跳：一律进入 UserFeedbackNode 收口
        return getBean("reactUserFeedbackNode");
    }

}

package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct 循环决策节点。
 *
 * <p>职责：检查终止条件，决定继续循环还是结束。
 *
 * <p>终止条件：
 * <ol>
 *   <li>已设置 stopReason</li>
 *   <li>达到 maxSteps</li>
 *   <li>达到 maxToolCalls</li>
 *   <li>AI 返回 finish 指令（DSL / JSON / 标签三种格式）</li>
 *   <li>发生错误</li>
 *   <li>循环完成（无工具调用 + 无终止指令）</li>
 * </ol>
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct LoopDecisionNode - 循环决策，当前步数: {}/{}", ctx.getStep(), ctx.getMaxSteps());

        // 1. 检查已有终止原因
        String stopReason = ctx.getStopReason();
        if (stopReason != null) {
            log.info("已设置终止原因: {}", stopReason);
            return router(requestParameter, ctx);
        }

        // 2. 检查最大步数
        if (ctx.getStep() >= ctx.getMaxSteps()) {
            log.info("达到最大步数: {}", ctx.getMaxSteps());
            ctx.setStopReason("max_steps");
            ctx.getResult().setMaxStepsReached(true);
            return router(requestParameter, ctx);
        }

        // 3. 检查最大工具调用次数
        if (ctx.getResult().getTotalToolCalls() >= ctx.getMaxToolCalls()) {
            log.info("达到最大工具调用次数: {}", ctx.getMaxToolCalls());
            ctx.setStopReason("max_tool_calls");
            return router(requestParameter, ctx);
        }

        // 4. 检查 assistant 内容是否包含 finish 指令
        String assistantContent = ctx.getAssistantContent() != null
                ? ctx.getAssistantContent().toString() : "";
        if (containsFinishCommand(assistantContent)) {
            log.info("AI 返回 finish 指令");
            ctx.setStopReason("finish");
            return router(requestParameter, ctx);
        }

        // 5. 检查错误
        if (ctx.getErrorMessage() != null) {
            log.info("发生错误: {}", ctx.getErrorMessage());
            ctx.setStopReason("error");
            return router(requestParameter, ctx);
        }

        // 6. 无工具调用 + 无终止指令 → 循环完成
        log.info("ReAct 循环完成，无更多工具调用");
        ctx.setStopReason("completed");
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {

        String stopReason = ctx.getStopReason();

        // 有终止原因 → UserFeedbackNode
        if (stopReason != null) {
            switch (stopReason) {
                case "user_stop":
                    ctx.getResult().setUserStopped(true);
                    break;
                case "idle_timeout":
                    ctx.getResult().setIdleTimeout(true);
                    break;
                case "max_steps":
                    ctx.getResult().setMaxStepsReached(true);
                    break;
                default:
                    break;
            }
            return getBean("reactUserFeedbackNode");
        }

        // 无终止原因 → 继续回到 AiCallNode
        log.info("继续 ReAct 循环，路由到 AiCallNode");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  终止指令检测
    // ═══════════════════════════════════════════════════════════════

    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) return false;
        String lower = content.toLowerCase();

        // DSL 风格: finish(message=...)
        if (lower.contains("finish(") || lower.contains("finish (")) return true;

        // JSON 风格: {"action": "finish"}
        if (lower.contains("\"action\"") && lower.contains("\"finish\"")) return true;

        // 标签风格: <answer>finish(...)</answer>
        if (lower.contains("<answer>") && lower.contains("finish")) return true;

        return false;
    }

}

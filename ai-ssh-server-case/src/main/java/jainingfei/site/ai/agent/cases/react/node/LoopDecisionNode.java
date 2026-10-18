package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    @Resource
    private IChatContextService chatContextService;

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

            if ("error".equals(stopReason) || "cancelled".equals(stopReason)) {
                intentService.markTaskInterrupted(sessionId);
                log.info("回合{}，任务态保持可续接 rootIntent={}", stopReason, taskState.getRootIntent());
                return;
            }
            if (hasToolResults && !taskState.isLastFeedbackFailed() && !taskState.isCompleted()) {
                // 附加闸门：不能只看"这回合碰过工具"——最后一轮工具失败、或结果把意图改判成
                // 非业务意图/另一个业务意图时，任务并没有真正达成，保持进行中让用户下一句可续接
                String blockReason = completionBlockReason(ctx, taskState);
                if (blockReason != null) {
                    log.info("回合未归档任务（{}）rootIntent={}", blockReason, taskState.getRootIntent());
                    return;
                }
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

    /**
     * 完成判定的附加闸门（修复"碰过工具即完成"的变体误判）。
     *
     * <p>两道条件：
     * <ol>
     *   <li>最后一轮工具结果必须是结构化成功（{@code success=true}）——TaskStateVO 的
     *       lastFeedbackFailed 是"最近一次反馈打点"，ToolCallNode 副作用异常等场景可能漏更新，
     *       这里直接以本回合收集到的最后一条结果为唯一判据；</li>
     *   <li>最后反馈后的会话意图必须仍是业务意图且与任务根意图一致——结果把意图改判成
     *       CHAT/UNKNOWN/CONTINUE 或另一个业务意图时，说明动作与任务目标不完全对应，不归档。</li>
     * </ol>
     *
     * @return 不满足归档条件的原因；可归档返回 null
     */
    private String completionBlockReason(DefaultReActFactory.DynamicContext ctx, TaskStateVO taskState) {
        List<EventTextUtil.ToolResultView> results = ctx.getToolResultsCollected();
        if (results == null || results.isEmpty()) {
            return "无工具结果";
        }
        EventTextUtil.ToolResultView lastResult = results.get(results.size() - 1);
        Map<String, Object> response = lastResult.response();
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            return "最后一轮工具执行未成功: " + lastResult.name();
        }
        IntentResultVO currentIntent = chatContextService.getSessionIntent(ctx.getSessionId());
        if (currentIntent == null || currentIntent.getIntent() == null) {
            return null;
        }
        IntentTypeEnumVO intent = currentIntent.getIntent();
        if (intent == IntentTypeEnumVO.CHAT
                || intent == IntentTypeEnumVO.UNKNOWN
                || intent == IntentTypeEnumVO.CONTINUE) {
            return "最后反馈意图不是业务意图: " + intent.name();
        }
        if (taskState.getRootIntent() != null && intent != taskState.getRootIntent()) {
            return "最后反馈意图与任务根意图不一致: " + intent.name() + " != " + taskState.getRootIntent().name();
        }
        return null;
    }

}

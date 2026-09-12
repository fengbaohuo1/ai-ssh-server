package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.intent.IntentService;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ReAct 工具治理节点 — 托管循环下的工具副作用收口（教学版 ToolCallNode 语义的 V3 映射）。
 *
 * <p>显式循环时代本节点负责"解析标签 + 白名单 + 执行工具 + 结果回灌"；V3 托管循环下
 * 工具已由 ADK Runner 执行完毕（AiCallNode 事件中继收集 FunctionResponse），本节点对
 * 收集到的结果统一执行<b>回合后置副作用</b>：
 * <ol>
 *   <li>最近命令入会话缓存（回合级 [最近执行的命令] 数据源）</li>
 *   <li>工具里程碑记录（关键事件）</li>
 *   <li>工具结果缓存（ToolResultProvider 摘要/时间线）</li>
 *   <li>DB 落库 + 环境事实/软件版本/失败信号记忆提取（2-8）</li>
 *   <li>意图反馈回路（结果 → 重分类，修正提示下回合消费）+ 任务态打点
 *       （updateTaskStateAfterFeedback：成功动作留痕/失败置位/重分类换根意图；
 *       completed 由 LoopDecisionNode 回合收口统一判定，避免托管循环中途误判完成）</li>
 * </ol>
 * 执行点后置不影响语义：托管循环回合内没有"下一轮注入"，反馈与提示只影响后续用户回合。
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IPromptService promptService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        List<EventTextUtil.ToolResultView> results = ctx.getToolResultsCollected();
        if (results == null || results.isEmpty()) {
            log.info("ReAct ToolCallNode - 无工具结果，跳过");
            return router(requestParameter, ctx);
        }

        log.info("ReAct ToolCallNode - 回合后置治理 {} 条工具结果", results.size());
        for (EventTextUtil.ToolResultView result : List.copyOf(results)) {
            applyToolSideEffects(ctx, result);
        }
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // V3 无循环回跳：治理完成后进入收尾决策节点
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具副作用
    // ═══════════════════════════════════════════════════════════════

    /** 单条工具结果副作用（原 ToolCallNode 执行后处理；V3 在 FR 收集后统一执行） */
    private void applyToolSideEffects(DefaultReActFactory.DynamicContext ctx, EventTextUtil.ToolResultView result) {
        try {
            String sessionId = ctx.getSessionId();
            String userId = ctx.getUserId();
            Map<String, Object> r = result.response();
            String command = stringValue(r.get("command"));
            // 工具返回结构因工具而异：executeCommand = {command, output, success}
            // 派发类工具（子智能体派发/批量/规划）= {success, result} 或 {success:false, error}
            String output = firstNonBlank(
                    stringValue(r.get("output")),
                    stringValue(r.get("result")),
                    stringValue(r.get("error")));
            boolean flaggedSuccess = Boolean.TRUE.equals(r.get("success"));
            // 结果文本 = 输出 + [success=...] 标记（里程碑/缓存/记忆共用）
            String resultText = output.isEmpty() ? "(命令无输出)" : output;
            resultText = resultText + "\n[success=" + flaggedSuccess + "]";

            // 最近命令 + 里程碑
            if (!command.isEmpty()) {
                chatContextService.pushRecentCommand(sessionId, command);
            }
            promptService.detectAndRecordMilestone(sessionId, "tool", resultText);

            // 工具结果缓存（工具摘要/时间线 Provider 数据源）
            boolean success = flaggedSuccess && !longTermMemoryService.isFailureContent(resultText);
            chatContextService.pushToolResult(sessionId, result.name(), command, success, resultText);

            // DB 落库 + 记忆提取（call id 取 FR id，与 assistant tool_calls 配对）
            longTermMemoryService.saveToolMessage(userId, sessionId, result.name(), result.id(), resultText, success);

            // 反馈回路 + 任务态打点：工具结果 → 意图重分类（修正提示下回合一次性注入）
            // → 任务步进状态机打点（成功追加留痕/失败置位/重分类换根意图，completed 由 LoopDecisionNode 收口）
            handleIntentFeedback(sessionId, resultText, result.name(), command);
        } catch (Exception e) {
            log.warn("工具副作用处理失败 sessionId={} tool={}", ctx.getSessionId(), result.name(), e);
        }
    }

    /**
     * 反馈回路（原显式循环 ToolCallNode 语义，改用会话缓存状态）+ 任务态打点。
     * <p>
     * 任务态打点不依赖会话意图是否存在（任务态按 sessionId 维护，意图识别失败也应留痕）。
     */
    private void handleIntentFeedback(String sessionId, String toolResult, String toolName, String command) {
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);

        IntentResultVO lastIntent = chatContextService.getSessionIntent(sessionId);
        IntentResultVO reclassified = null;
        if (lastIntent != null) {
            reclassified = intentService.reportFeedback(sessionId, lastIntent, success, toolResult);
            if (reclassified != null) {
                log.info("反馈回路触发重分类: {} -> {} (conf={})",
                        lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());
                chatContextService.setSessionIntent(sessionId, reclassified);
                chatContextService.setPendingIntentCorrection(sessionId, String.format(
                        "[用户意图已修正] %s → %s（置信度 %.2f）",
                        lastIntent.getIntent().getLabel(),
                        reclassified.getIntent().getLabel(),
                        reclassified.getConfidence()));
            }
        }

        // 任务态打点：成功动作摘要入 steps 留痕、失败置位、重分类换根意图（规则在 ContextTracker）
        String actionSummary = command != null && !command.isBlank() ? command : toolName;
        intentService.updateTaskStateAfterFeedback(sessionId, success, reclassified, truncate(actionSummary, 80));
    }

    /** 动作摘要截断（steps 留痕只取头部，防命令过长膨胀上下文展示） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 对象 → 字符串（null 安全） */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 取第一个非空白字符串（派发类工具没有 output 时回退 result / error） */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

}

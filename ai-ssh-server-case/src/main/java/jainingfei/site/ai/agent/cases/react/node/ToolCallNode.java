package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.intent.IntentService;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具执行节点 — 显式循环中的工具执行者。
 *
 * <p>职责：
 * <ol>
 *   <li>解析 AiCallNode 从 {@code <tool_calls>} 标签解析出的工具调用</li>
 *   <li>白名单过滤（LLM 幻觉工具名直接丢弃）</li>
 *   <li>通过 {@link SshExecuteAdkTool#executeCommand} 公共入口执行工具（危险命令拦截在工具层）</li>
 *   <li>记录最近命令 + 工具结果里程碑</li>
 *   <li>结果回灌（lastToolResultText）→ 路由回 AiCallNode 进入下一轮</li>
 * </ol>
 *
 * <p>全部未执行（幻觉工具）→ 设置 {@code no_matching_tool} 终止循环，防止死循环。
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    /** 已注册到 ADK 的工具名白名单（LLM 可能幻觉出其他名字，需过滤） */
    private static final java.util.Set<String> REGISTERED_TOOLS = java.util.Set.of("executeCommand");

    /** 工具结果截断长度 */
    private static final int MAX_RESULT_LENGTH = 4000;

    /** 回灌结果累积上限（修复②）：多命令累积时超出则保留头尾、丢弃中段，防下一轮输入暴涨 */
    private static final int MAX_ACCUMULATED_LENGTH = 8000;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        List<Map<String, Object>> toolCalls = ctx.getCurrentToolCalls();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - 无工具调用，跳过");
            return router(requestParameter, ctx);
        }

        log.info("ReAct ToolCallNode - 执行 {} 个工具调用", toolCalls.size());
        SseEmitter emitter = ctx.getEmitter();
        String sessionId = ctx.getSessionId();
        int executedCount = 0;

        for (Map<String, Object> call : toolCalls) {
            String toolName = call.get("name") instanceof String s ? s : "unknown";

            // 白名单过滤——LLM 幻觉工具直接丢弃
            if (!isRegisteredTool(toolName)) {
                log.warn("跳过未注册的工具调用: {} — LLM 幻觉，忽略", toolName);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> args = call.get("args") instanceof Map
                    ? (Map<String, Object>) call.get("args") : Map.of();
            String command = args.get("command") instanceof String c ? c : "";

            if (command == null || command.isEmpty()) {
                log.warn("工具参数缺失 command: {}", call);
                continue;
            }

            // 执行工具（危险命令拦截在工具层 doExecute 内）
            log.info("执行 SSH 命令: command={}", command);
            Map<String, Object> result = sshExecuteAdkTool.executeCommand(sessionId, command);
            String resultText = formatResult(result);
            resultText = truncate(resultText, MAX_RESULT_LENGTH);
            Object callId = call.get("id");

            // 记录最近命令（记录命令本身，而非输出——显式循环下能拿到命令）
            ctx.addRecentCommand(command);

            // 记录工具结果里程碑（工具报错等关键事件）
            promptService.detectAndRecordMilestone(sessionId, "tool", resultText);

            // 推送工具结果到会话级缓存（命令+成败供时间线展示，结果供摘要生成）
            boolean success = Boolean.TRUE.equals(result.get("success"))
                    && !longTermMemoryService.isFailureContent(resultText);
            chatContextService.pushToolResult(sessionId, toolName, command, success, resultText);

            // 工具消息落库 + 环境事实/软件版本/失败信号记忆提取（2-8）
            longTermMemoryService.saveToolMessage(
                    ctx.getUserId(), sessionId, toolName,
                    callId != null ? String.valueOf(callId) : null,
                    resultText, success);

            // 反馈回路：根据工具结果判定当前意图是否走偏，必要时重分类
            // （显式循环下工具在本节点执行，故挂在这里而非 AiCallNode——教程中 ADK 内部
            //   执行工具、结果出现在 AiCallNode 的 stateDelta，本项目位置不同但语义一致）
            handleIntentFeedback(ctx, resultText);

            // 存储工具结果（累积，UserFeedbackNode 汇总；id 与 assistant tool_calls 配对）
            Map<String, Object> toolResultInfo = new HashMap<>();
            if (callId != null) {
                toolResultInfo.put("id", callId);
            }
            toolResultInfo.put("name", toolName);
            toolResultInfo.put("content", resultText);
            toolResultInfo.put("status", "success");
            ctx.getCurrentToolResults().add(toolResultInfo);

            // 写入业务侧历史（tool 消息，与 assistant 的 tool_calls 同组，供裁剪器完整保留）
            if (callId != null) {
                ctx.appendToolMessage(String.valueOf(callId), resultText);
            }

            // 结果回灌下一轮：命令 + 输出（多条命令累积——覆盖会导致 LLM 只见最后一条）。
            // 累积设上限（修复②）：超出保留头尾、丢弃中段，防大输出把下一轮输入撑爆
            String roundResult = "$ " + command + "\n" + resultText;
            String prev = ctx.getLastToolResultText();
            String merged = prev.isEmpty() ? roundResult : prev + "\n\n" + roundResult;
            ctx.setLastToolResultText(capAccumulated(merged));

            // 发送完整结果 Map（command/output/success/suggestion）——前端协议约定 r 为 JSON 对象，
            // jsonField(r, "output") 取结果、jsonField(r, "success") 判断成败
            sendToolResultEvent(emitter, toolName, result);
            executedCount++;
        }

        // 全部未识别（LLM 幻觉）→ 强制终止，防止死循环
        if (executedCount == 0) {
            log.warn("本轮 {} 个工具调用全部未被识别——LLM 幻觉，强制终止", toolCalls.size());
            ctx.setStopReason("no_matching_tool");
        }

        // 清除本轮工具调用标记，避免重复路由
        ctx.getCurrentToolCalls().clear();

        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {

        // 终止条件：已设置 stopReason（幻觉/错误等）
        if (ctx.getStopReason() != null) {
            log.info("已设置终止原因: {}，路由到 LoopDecisionNode", ctx.getStopReason());
            return getBean("reactLoopDecisionNode");
        }

        // 终止条件：最大步数
        if (ctx.getStep() >= ctx.getMaxSteps()) {
            log.info("达到最大步数 {}，路由到 LoopDecisionNode", ctx.getMaxSteps());
            ctx.setStopReason("max_steps");
            return getBean("reactLoopDecisionNode");
        }

        // 终止条件：最大工具调用次数
        if (ctx.getResult().getTotalToolCalls() >= ctx.getMaxToolCalls()) {
            log.info("达到最大工具调用次数 {}，路由到 LoopDecisionNode", ctx.getMaxToolCalls());
            ctx.setStopReason("max_tool_calls");
            return getBean("reactLoopDecisionNode");
        }

        // 正常情况：回到 AiCallNode 进入下一轮（工具结果已回灌 lastToolResultText）
        log.info("工具处理完成，回到 AiCallNode 继续下一轮");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private static boolean isRegisteredTool(String toolName) {
        return REGISTERED_TOOLS.contains(toolName);
    }

    /** 工具结果 → 展示文本（输出 + 成功标记） */
    private static String formatResult(Map<String, Object> result) {
        if (result == null) return "No result";
        Object output = result.get("output");
        Object success = result.get("success");
        StringBuilder sb = new StringBuilder();
        if (output != null && !output.toString().isEmpty()) {
            sb.append(output);
        } else {
            sb.append("(命令无输出)");
        }
        if (success != null) {
            sb.append("\n[success=").append(success).append("]");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        // head/tail 保留 + 精确省略计数（替代旧"掐头"）：输出头部的命令回显与尾部的最终结论
        // 比中段信息量更大；省略标记让模型知道内容被截断，可要求补充
        return TextRetentionUtil.retainHeadTail(s, max);
    }

    /**
     * 回灌结果累积上限保护（修复②）：lastToolResultText 会在下一轮整体拼入 userContent，
     * 单条结果虽已截 4000 字符，但单轮最多 10 次调用仍可累积 4 万字符。
     * 超限保留头尾、丢弃中段——最近的输出和最老的输出都比中段更有信息量
     * （命令回显在头部、最终结果在尾部），统一复用 {@link TextRetentionUtil}。
     */
    private static String capAccumulated(String text) {
        return TextRetentionUtil.retainHeadTail(text, MAX_ACCUMULATED_LENGTH);
    }

    /**
     * 反馈回路：根据工具执行结果判定当前意图是否需要重分类。
     * <p>
     * 仅在本轮已有意图识别结果时触发；reportFeedback 返回非 null 表示已重分类，
     * 此时更新 DynamicContext 的当前意图，并置位"意图修正提示"供下一轮一次性注入。
     * <p>
     * 流程：
     * <pre>
     *   工具执行完(result)
     *     ├─ 无本轮意图结果 → 直接返回
     *     ├─ 判定 success（非空 && 不像意图走偏）
     *     └─ intentService.reportFeedback(...)
     *          ├─ 返回 null  → 维持原意图
     *          └─ 返回新结果 → 更新 currentIntent / currentIntentResult
     *                        → 置位 pendingIntentCorrection（下一轮 AiCallNode 注入后清空）
     * </pre>
     * 案例：意图 CONFIGURE，工具结果 "No such file" → success=false →
     *       reportFeedback 用候选 MONITOR 递补 → 下一轮 Prompt 注入 "[用户意图已修正] 配置修改 → 监控查看"。
     */
    private void handleIntentFeedback(DefaultReActFactory.DynamicContext ctx, String toolResult) {
        IntentResultVO lastIntent = ctx.getCurrentIntentResult();
        if (lastIntent == null) {
            return;
        }
        // 复用 IntentService 的失败特征判定，保持两处逻辑一致
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);

        IntentResultVO reclassified = intentService.reportFeedback(
                ctx.getSessionId(), lastIntent, success, toolResult);
        if (reclassified != null) {
            log.info("反馈回路触发重分类: {} -> {} (conf={})",
                    lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());
            ctx.setCurrentIntent(reclassified.getIntent().name());
            ctx.setCurrentIntentResult(reclassified);
            // 一次性注入"意图已修正"提示（消费即清空），让下一轮主模型感知意图变化
            ctx.setPendingIntentCorrection(String.format(
                    "[用户意图已修正] %s → %s（置信度 %.2f）",
                    lastIntent.getIntent().getLabel(),
                    reclassified.getIntent().getLabel(),
                    reclassified.getConfidence()));
        }
    }
}

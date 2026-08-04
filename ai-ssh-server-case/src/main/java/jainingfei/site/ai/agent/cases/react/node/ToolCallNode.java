package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
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

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private IPromptService promptService;

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

            // 记录最近命令（记录命令本身，而非输出——显式循环下能拿到命令）
            ctx.addRecentCommand(command);

            // 记录工具结果里程碑（工具报错等关键事件）
            promptService.detectAndRecordMilestone(sessionId, "tool", resultText);

            // 存储工具结果（累积，UserFeedbackNode 汇总）
            Map<String, Object> toolResultInfo = new HashMap<>();
            toolResultInfo.put("name", toolName);
            toolResultInfo.put("content", resultText);
            toolResultInfo.put("status", "success");
            ctx.getCurrentToolResults().add(toolResultInfo);

            // 结果回灌下一轮：命令 + 输出（多条命令累积——覆盖会导致 LLM 只见最后一条）
            String roundResult = "$ " + command + "\n" + resultText;
            String prev = ctx.getLastToolResultText();
            ctx.setLastToolResultText(prev.isEmpty() ? roundResult : prev + "\n\n" + roundResult);

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
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n... (truncated, total " + s.length() + " chars)" : s;
    }
}

package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具执行节点。
 *
 * <p>职责：
 * <ol>
 *   <li>处理 AiCallNode 放入上下文的工具调用/结果</li>
 *   <li>支持两种模式：
 *     <ul>
 *       <li>ADK 自动执行（主力）：工具结果已存在，做日志记录 + 清理</li>
 *       <li>手动执行（兜底，对付差模型）：直接调用 SshExecuteAdkTool</li>
 *     </ul>
 *   </li>
 *   <li>路由：回到 AiCallNode 继续对话 或 到 LoopDecisionNode 完成</li>
 * </ol>
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool sshExecuteAdkTool;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        List<Map<String, Object>> toolCalls = ctx.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = ctx.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - 无工具调用，跳过");
            return router(requestParameter, ctx);
        }

        log.info("ReAct ToolCallNode - 处理 {} 个工具调用，已有 {} 个结果",
                toolCalls.size(), toolResults != null ? toolResults.size() : 0);

        // ADK 自动执行模式：工具已被 ADK 内部执行
        boolean adkAutoExecuted = toolResults != null && !toolResults.isEmpty();
        int executedCount = 0;

        if (adkAutoExecuted) {
            log.info("工具已被 ADK 自动执行，处理已有结果");
            handleAdkResults(ctx, toolCalls, toolResults);
            executedCount = toolCalls.size();
        } else {
            log.info("工具未被 ADK 执行（fallback），尝试手动执行");
            executedCount = handleManualExecution(ctx, toolCalls);
        }

        // 如果一个工具都没执行成功（全部是未识别的 LLM 幻觉），停止循环
        if (executedCount == 0) {
            log.warn("本轮 {} 个工具调用全部未被识别——LLM 幻觉，强制终止", toolCalls.size());
            ctx.setStopReason("no_matching_tool");
        }

        // 清除本轮工具调用标记
        ctx.getCurrentToolCalls().clear();

        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {

        // 检查是否达到最大步数
        if (ctx.getStep() >= ctx.getMaxSteps()) {
            log.info("达到最大步数 {}，路由到 LoopDecisionNode", ctx.getMaxSteps());
            ctx.setStopReason("max_steps");
            return getBean("reactLoopDecisionNode");
        }

        // 回到 AiCallNode 继续对话
        log.info("工具处理完成，回到 AiCallNode 继续对话");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADK 自动执行模式
    // ═══════════════════════════════════════════════════════════════

    private void handleAdkResults(DefaultReActFactory.DynamicContext ctx,
                                  List<Map<String, Object>> toolCalls,
                                  List<Map<String, Object>> toolResults) {
        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> r : toolResults) {
            String id = (String) r.get("id");
            if (id != null) resultMap.put(id, r);
        }

        for (Map<String, Object> tc : toolCalls) {
            String id = (String) tc.get("id");
            String name = (String) tc.get("name");
            Map<String, Object> matched = resultMap.get(id);
            if (matched != null) {
                log.info("ADK 工具结果: id={} name={} resultLen={}",
                        id, name, String.valueOf(matched.getOrDefault("content", "")).length());
            } else {
                log.warn("未找到工具结果: id={} name={}", id, name);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  手动执行模式（兜底）
    // ═══════════════════════════════════════════════════════════════

    private int handleManualExecution(DefaultReActFactory.DynamicContext ctx,
                                       List<Map<String, Object>> toolCalls) throws Exception {
        SseEmitter emitter = ctx.getEmitter();
        String sessionId = ctx.getSessionId();
        int executedCount = 0;

        for (Map<String, Object> tc : toolCalls) {
            String id = (String) tc.get("id");
            String name = (String) tc.get("name");
            if (id == null || name == null) continue;

            // 仅支持 executeCommand
            if (!"executeCommand".equals(name)) {
                log.warn("未知工具: {}", name);
                continue;
            }

            try {
                // 通过 sessionConnections 获取 connectionId 并直接调用
                // 这里依赖 SshExecuteAdkTool 的静态绑定
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) tc.get("args");
                String command = args != null ? (String) args.get("command") : "";

                if (command == null || command.isEmpty()) {
                    log.warn("手动执行：command 为空");
                    continue;
                }

                log.info("手动执行 SSH 命令: {}", command);
                Map<String, Object> result = sshExecuteAdkTool.runAsync(
                        Map.of("command", command),
                        null  // ToolContext 不可用于手动执行
                ).blockingGet();

                String resultContent = formatResult(result);
                Map<String, Object> toolResultInfo = new HashMap<>();
                toolResultInfo.put("id", id);
                toolResultInfo.put("name", name);
                toolResultInfo.put("content", resultContent);
                toolResultInfo.put("status", "success");
                ctx.getCurrentToolResults().add(toolResultInfo);

                sendToolResultEvent(emitter, name, resultContent);
                executedCount++;
            } catch (Exception e) {
                log.error("手动执行工具失败: name={}", name, e);
                sendToolResultEvent(emitter, name, "Error: " + e.getMessage());
            }
        }

        return executedCount;
    }

    private String formatResult(Map<String, Object> result) {
        if (result == null) return "No result";
        Object output = result.get("output");
        Object success = result.get("success");
        StringBuilder sb = new StringBuilder();
        if (output != null) sb.append(output);
        if (success != null) sb.append("\n[success=").append(success).append("]");
        return sb.toString();
    }
}

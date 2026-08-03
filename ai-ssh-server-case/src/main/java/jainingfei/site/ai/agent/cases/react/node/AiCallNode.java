package jainingfei.site.ai.agent.cases.react.node;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.agents.RunConfig;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
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
 * AI 调用节点 — ReAct 循环的核心。
 *
 * <p>职责：
 * <ol>
 *   <li>从 DefaultArmoryFactory 获取 ADK Runner</li>
 *   <li>绑定 terminalSessionId 到 SshExecuteAdkTool</li>
 *   <li>调用 runner.runAsync() 获取事件流</li>
 *   <li>处理文本内容 → SSE text 事件</li>
 *   <li>双重检测工具结果：
 *     <ul>
 *       <li>ADK 标准：event.functionCalls() / event.functionResponses()</li>
 *       <li>Spring AI stateDelta：event.actions().stateDelta()（兜底）</li>
 *     </ul>
 *   </li>
 *   <li>更新步数和工具调用统计</li>
 *   <li>路由：有工具调用 → ToolCallNode，无工具调用 → LoopDecisionNode</li>
 * </ol>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private IPromptService promptService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", ctx.getStep() + 1);

        String agentId = ctx.getAgentId();
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        String terminalSessionId = ctx.getTerminalSessionId();

        // 1. 获取 ADK Runner
        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (registerVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }
        InMemoryRunner runner = registerVO.getRunner();

        // 2. 绑定终端会话到工具（session 已由 RootNode 通过 ChatService 创建/校验）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.bindSession(sessionId, terminalSessionId);
        }

        // 4. 重置当前轮缓冲
        ctx.resetRoundBuffers();

        // 5. 构建注入了动态上下文的用户消息
        //    采集环境（uname/whoami/pwd）+ 最近命令 + 里程碑 → 拼成消息前缀
        String lastUserMessage = requestParameter.getMessage();

        // 记录用户消息的里程碑（用户纠偏、任务切换等关键事件）
        promptService.detectAndRecordMilestone(sessionId, "user", lastUserMessage);

        // 构建富化消息：环境 + 最近命令 + 里程碑 → 前缀 + 原始消息
        String enrichedMessage = promptService.buildEnrichedMessage(
                lastUserMessage, sessionId, terminalSessionId, ctx.getRecentCommands());
        log.debug("注入动态上下文后消息长度: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        Content userContent = Content.fromParts(Part.fromText(enrichedMessage));

        // 6. 调用 ADK Runner 并处理事件流
        SseEmitter emitter = ctx.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        java.util.concurrent.atomic.AtomicInteger roundToolCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        boolean hasError = false;

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            // 使用 blockingForEach（与 ChatService 完全一致，已验证可行）
            runner.runAsync(
                    userId, sessionId, userContent,
                    RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build()
            ).blockingForEach(event -> {
                // 7.1 处理文本内容
                String text = eventText(event);
                if (!text.isEmpty()) {
                    textAccumulator.append(text);
                    ctx.getAssistantContent().append(text);
                    sendText(emitter, text, textAccumulator.toString(), null);
                }

                // 7.2 ADK 标准方式：从 functionCalls/functionResponses 检测工具
                List<FunctionCall> fcs = event.functionCalls();
                List<FunctionResponse> frs = event.functionResponses();

                for (FunctionCall fc : fcs) {
                    String toolName = fc.name().orElse("unknown");
                    String toolCallId = "call_" + toolName + "_" + System.currentTimeMillis();

                    // 只记录已注册的工具，防止 LLM 幻觉（如 "ssh"）导致死循环
                    if (!isRegisteredTool(toolName)) {
                        log.warn("跳过未注册的工具调用: {} — LLM 幻觉，忽略", toolName);
                        continue;
                    }

                    Map<String, Object> args = fc.args()
                            .filter(a -> a instanceof Map)
                            .map(a -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> m = (Map<String, Object>) a;
                                return m;
                            })
                            .orElse(Map.of());

                    // 记录工具调用
                    Map<String, Object> toolCallInfo = new HashMap<>();
                    toolCallInfo.put("id", toolCallId);
                    toolCallInfo.put("name", toolName);
                    toolCallInfo.put("args", args);
                    ctx.getCurrentToolCalls().add(toolCallInfo);

                    sendToolCallEvent(emitter, toolName, args);
                    roundToolCalls.incrementAndGet();
                    ctx.incrementTotalToolCalls();
                }

                for (FunctionResponse fr : frs) {
                    String toolName = fr.name().orElse("unknown");
                    String toolCallId = "call_" + toolName + "_" + System.currentTimeMillis();
                    Object responseData = fr.response().isPresent() ? fr.response().get() : "";

                    Map<String, Object> toolResultInfo = new HashMap<>();
                    toolResultInfo.put("id", toolCallId);
                    toolResultInfo.put("name", toolName);
                    toolResultInfo.put("content", responseData);
                    toolResultInfo.put("status", "success");
                    ctx.getCurrentToolResults().add(toolResultInfo);

                    sendToolResultEvent(emitter, toolName, responseData);

                    // 记录执行的命令到最近命令列表 + 工具结果里程碑
                    String toolOutput = responseData instanceof String s ? s : String.valueOf(responseData);
                    if ("executeCommand".equals(toolName) && !toolOutput.isEmpty()) {
                        ctx.addRecentCommand(toolOutput.length() > 80
                                ? toolOutput.substring(0, 80) + "..." : toolOutput);
                    }
                    promptService.detectAndRecordMilestone(sessionId, "tool", toolOutput);
                }

                // 7.3 兜底：从 stateDelta 检测（Spring AI 自动执行模式）
                EventActions actions = event.actions();
                if (actions != null) {
                    Map<String, Object> stateDelta = actions.stateDelta();
                    if (stateDelta != null && !stateDelta.isEmpty()) {
                        for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
                            String stateKey = entry.getKey();
                            Object stateValue = entry.getValue();
                            if ("REMOVED".equals(stateValue)) continue;

                            String toolName = stateKey.endsWith("_result")
                                    ? stateKey.substring(0, stateKey.length() - 7) : stateKey;

                            // stateDelta 也可能含 Spring AI 流式 phantom call，需同样过滤
                            if (!isRegisteredTool(toolName)) {
                                continue;
                            }

                            String toolCallId = "call_" + stateKey + "_" + System.currentTimeMillis();
                            String resultContent = stateValue instanceof String
                                    ? (String) stateValue : String.valueOf(stateValue);

                            if (!ctx.getCurrentToolCalls().stream()
                                    .anyMatch(tc -> toolName.equals(tc.get("name")))) {
                                Map<String, Object> toolCallInfo = new HashMap<>();
                                toolCallInfo.put("id", toolCallId);
                                toolCallInfo.put("name", toolName);
                                toolCallInfo.put("args", "");
                                ctx.getCurrentToolCalls().add(toolCallInfo);

                                sendToolCallEvent(emitter, toolName, null);
                                sendToolResultEvent(emitter, toolName, resultContent);
                                roundToolCalls.incrementAndGet();
                                ctx.incrementTotalToolCalls();

                                // 记录执行的命令 + 工具结果里程碑
                                if ("executeCommand".equals(toolName) && !resultContent.isEmpty()) {
                                    ctx.addRecentCommand(resultContent.length() > 80
                                            ? resultContent.substring(0, 80) + "..." : resultContent);
                                }
                                promptService.detectAndRecordMilestone(sessionId, "tool", resultContent);
                            }
                        }
                    }
                }
            });

            log.info("ADK Runner 事件流处理完成");

        } catch (Exception e) {
            log.error("ADK Runner 调用失败: {}", e.getMessage(), e);
            hasError = true;
            ctx.setErrorMessage("ADK Runner error: " + e.getMessage());
            ctx.setStopReason("error");
        }

        // 8. 更新步数和统计
        ctx.incrementStep();
        ctx.getResult().setTotalSteps(ctx.getStep());
        ctx.getResult().setTotalToolCalls(
                ctx.getResult().getTotalToolCalls() + roundToolCalls.get()
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                ctx.getStep(), roundToolCalls.get(), textAccumulator.length());

        // 9. ADK 内部已完成完整 ReAct 循环，直接终止
        if (!hasError) {
            ctx.setStopReason("completed");
        }
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // ADK 内部已完成完整 ReAct 循环（LLM ↔ tool），直接走终止链路
        log.info("ADK 内部 ReAct 已完成，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 从 Event 提取纯文本（去除 tool_calls 标签） */
    private static String eventText(Event e) {
        return e.content()
                .flatMap(c -> c.parts())
                .map(ps -> ps.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .reduce("", String::concat))
                .map(t -> TOOL_CALLS_TAG.matcher(t).replaceAll(""))
                .orElse("");
    }

    private static final java.util.regex.Pattern TOOL_CALLS_TAG =
            java.util.regex.Pattern.compile("</?tool_calls>");

    /** 已注册到 ADK 的工具名白名单（LLM 可能幻觉出其他名字，需过滤） */
    private static final java.util.Set<String> REGISTERED_TOOLS = java.util.Set.of("executeCommand");

    private static boolean isRegisteredTool(String toolName) {
        return REGISTERED_TOOLS.contains(toolName);
    }
}

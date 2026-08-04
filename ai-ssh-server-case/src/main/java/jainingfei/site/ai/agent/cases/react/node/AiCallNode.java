package jainingfei.site.ai.agent.cases.react.node;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 调用节点 — 显式 ReAct 循环的每一轮。
 *
 * <p>单轮模式（FixedSpringAI singleTurnMode）下，ADK 的 runAsync() 每次只跑一轮 LLM——
 * 工具调用以 {@code <tool_calls>} 文本标签形式出现在模型输出中，不会触发 ADK 内部工具循环。
 * 工具执行与循环推进由 Case 层显式控制。
 *
 * <p>每轮职责：
 * <ol>
 *   <li>富化用户消息（环境采集 + 里程碑 + 最近命令）——每轮都做，上下文递增注入</li>
 *   <li>拼接上一轮工具执行结果（如有）</li>
 *   <li>调用 runner.runAsync() 单轮生成</li>
 *   <li>剥离 {@code <tool_calls>} 标签发 SSE 文本，解析标签为工具调用</li>
 *   <li>路由：有工具调用 → ToolCallNode；无 → LoopDecisionNode</li>
 * </ol>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具调用标签块：{@code <tool_calls>...每行一个 JSON...</tool_calls>} */
    private static final Pattern TOOL_CALLS_BLOCK =
            Pattern.compile("<tool_calls>\\s*(.*?)\\s*</tool_calls>", Pattern.DOTALL);

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

        // 3. 清空当前轮工具调用标记（防上轮残留；工具结果不清——多轮累积供 UserFeedbackNode 汇总）
        ctx.getCurrentToolCalls().clear();

        // 4. 富化用户消息（每轮都做：环境 + 里程碑 + 最近命令 → 递增注入）
        String lastUserMessage = requestParameter.getMessage();

        // 记录用户消息的里程碑（用户纠偏、任务切换等关键事件）
        promptService.detectAndRecordMilestone(sessionId, "user", lastUserMessage);

        // 构建富化消息：前缀 + 原始消息 + （上一轮工具执行结果）
        String enrichedMessage = promptService.buildEnrichedMessage(
                lastUserMessage, sessionId, terminalSessionId, ctx.getRecentCommands());

        StringBuilder fullContent = new StringBuilder(enrichedMessage);
        String lastResult = ctx.getLastToolResultText();
        if (lastResult != null && !lastResult.isEmpty()) {
            fullContent.append("\n\n[上一轮工具执行结果]\n").append(lastResult);
        }
        // 已消费本轮工具结果——清空，本轮新执行的结果由 ToolCallNode 重新累积
        ctx.setLastToolResultText("");
        log.debug("本轮 LLM 输入长度: {} (原始: {}, 富化: {})",
                fullContent.length(), lastUserMessage.length(), enrichedMessage.length());

        Content userContent = Content.fromParts(Part.fromText(fullContent.toString()));

        // 5. 调用 ADK Runner（单轮模式：只生成，不执行工具）
        SseEmitter emitter = ctx.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        java.util.concurrent.atomic.AtomicInteger roundToolCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        boolean hasError = false;

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            runner.runAsync(
                    userId, sessionId, userContent,
                    RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build()
            ).blockingForEach(event -> {
                // 5.1 提取模型输出（含 <tool_calls> 标签，若模型本轮请求工具）
                String raw = eventText(event);
                if (raw.isEmpty()) return;

                // 5.2 解析工具调用标签 → 工具调用列表
                List<Map<String, Object>> parsedCalls = parseToolCalls(raw);
                for (Map<String, Object> call : parsedCalls) {
                    String toolName = call.get("name") instanceof String s ? s : "unknown";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = call.get("args") instanceof Map
                            ? (Map<String, Object>) call.get("args") : Map.of();

                    Map<String, Object> toolCallInfo = new HashMap<>();
                    toolCallInfo.put("name", toolName);
                    toolCallInfo.put("args", args);
                    ctx.getCurrentToolCalls().add(toolCallInfo);

                    sendToolCallEvent(emitter, toolName, args);
                    roundToolCalls.incrementAndGet();
                    ctx.incrementTotalToolCalls();
                    log.info("模型请求工具: {} args={}", toolName, args);
                }

                // 5.3 剥离标签后的纯文本 → SSE text（标签不展示给前端）
                // 注意：不能 trim——chunk 尾部的换行是模型的真实输出（表格/段落分隔），trim 会杀光换行
                String clean = TOOL_CALLS_BLOCK.matcher(raw).replaceAll("");
                if (!clean.isEmpty()) {
                    textAccumulator.append(clean).append("\n");
                    ctx.getAssistantContent().append(clean).append("\n");
                    sendText(emitter, clean, textAccumulator.toString(), null);
                }
            });

            log.info("ADK Runner 本轮事件流处理完成，本轮工具请求 {} 个", roundToolCalls.get());

        } catch (Exception e) {
            log.error("ADK Runner 调用失败: {}", e.getMessage(), e);
            hasError = true;
            ctx.setErrorMessage("ADK Runner error: " + e.getMessage());
            ctx.setStopReason("error");
        }

        // 6. 更新步数和统计
        ctx.incrementStep();
        ctx.getResult().setTotalSteps(ctx.getStep());
        ctx.getResult().setTotalToolCalls(
                ctx.getResult().getTotalToolCalls() + roundToolCalls.get()
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具请求 {} 次，文本长度 {}",
                ctx.getStep(), roundToolCalls.get(), textAccumulator.length());

        // 7. 路由
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // 显式循环：有工具调用 → ToolCallNode 执行；无 → LoopDecisionNode 终止判断
        if (!ctx.getCurrentToolCalls().isEmpty()) {
            log.info("本轮 {} 个工具调用，路由到 ToolCallNode", ctx.getCurrentToolCalls().size());
            return getBean("reactToolCallNode");
        }
        log.info("无工具调用，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 从 Event 提取文本（拼接所有 text part） */
    private static String eventText(Event e) {
        return e.content()
                .flatMap(c -> c.parts())
                .map(ps -> ps.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .reduce("", String::concat))
                .orElse("");
    }

    /**
     * 解析 {@code <tool_calls>} 标签块，每行一个 JSON：{@code {"name":..., "args":{...}}}。
     * 解析失败的标签行丢弃（记录 warn）——模型输出格式不稳时不影响主流程。
     */
    private static List<Map<String, Object>> parseToolCalls(String raw) {
        List<Map<String, Object>> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALLS_BLOCK.matcher(raw);
        while (matcher.find()) {
            String body = matcher.group(1).trim();
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> call = MAPPER.readValue(line, Map.class);
                    calls.add(call);
                } catch (Exception e) {
                    log.warn("解析工具调用 JSON 失败，已丢弃: {}", line);
                }
            }
        }
        return calls;
    }
}

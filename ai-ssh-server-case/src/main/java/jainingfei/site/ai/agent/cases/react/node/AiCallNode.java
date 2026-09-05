package jainingfei.site.ai.agent.cases.react.node;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.fasterxml.jackson.databind.ObjectMapper;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 调用节点 — 显式 ReAct 循环的每一轮。
 *
 * <p>单轮模式（FixedSpringAI singleTurnMode）下，ADK 的 runAsync() 每次只跑一轮 LLM——
 * 工具调用以 {@code <tool_calls>} 文本标签形式出现在模型输出中，不会触发 ADK 内部工具循环。
 * 工具执行与循环推进由 Case 层显式控制。
 *
 * <p>每轮职责（doApply 为纯编排，每步一个方法）：
 * <ol>
 *   <li>准备：{@link #prepareRunner} 取 Runner + {@link #bindTerminalSession} 绑定终端会话</li>
 *   <li>识别：{@link #classifyIntent} 意图识别（仅第 1 轮）</li>
 *   <li>裁剪：{@link #trimMessageHistory} 业务侧历史（供 ContextProvider 消费）</li>
 *   <li>组装：{@link #buildFirstRoundContent}（第 1 轮全量动态段）/ {@link #buildSubsequentRoundContent}
 *       （后续轮增量动态段 + 上一轮工具结果回灌）——统一"稳定段在前、动态段在后、\n---\n 分隔"的
 *       缓存友好布局（2-9）：system + 历史 + 稳定段前缀逐字节稳定，命中 LLM Prompt Cache</li>
 *   <li>调用：{@link #invokeRunner} 单轮生成，事件流处理（{@link #handleRunnerEvent}）</li>
 *   <li>统计：{@link #updateStats} 更新步数与工具调用数</li>
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

    /** 工具调用 id 序列（全局唯一，用于业务侧历史中 assistant tool_calls 与 tool 结果的配对） */
    private static final AtomicInteger TOOL_CALL_SEQ = new AtomicInteger(0);

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /**
     * 单轮 Runner 调用结果。
     *
     * @param roundToolCalls  本轮模型请求的工具调用数
     * @param accumulatedText 本轮累积的模型输出文本（剥离工具调用标签后）
     */
    private record RoundResult(int roundToolCalls, String accumulatedText) {
    }

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", ctx.getStep() + 1);

        // 1. 准备：Runner + 最新用户消息 + 终端会话绑定
        AiAgentRegisterVO registerVO = prepareRunner(ctx.getAgentId());
        String lastUserMessage = requestParameter.getMessage();
        bindTerminalSession(ctx);

        // 2. 意图识别（仅第 1 轮：显式循环下后续轮消息仍是原文，识别结果由反馈回路负责修正）
        if (ctx.getStep() == 0) {
            classifyIntent(registerVO, ctx, lastUserMessage);
        }

        // 3. 清空本轮工具调用标记（防上轮残留；工具结果不清——多轮累积供 UserFeedbackNode 汇总）
        ctx.getCurrentToolCalls().clear();
        trimMessageHistory(ctx);

        // 4. 组装本轮 userContent（每轮都做；第 1 轮与后续轮策略不同，见
        //    buildFirstRoundContent / buildSubsequentRoundContent）——
        //    统一布局：稳定段（原始消息 [+ 上一轮工具结果]）在前，动态段在后，"\n---\n" 分隔
        Content userContent = buildRoundUserContent(lastUserMessage, ctx);

        // 5. 调用 ADK Runner（单轮模式：只生成，不执行工具）
        log.info("调用 ADK Runner，用户消息: {}", truncateForLog(lastUserMessage, 200));
        log.debug("本轮 LLM 输入长度: {} (原始: {})", textLength(userContent), lastUserMessage.length());
        RoundResult round = invokeRunner(registerVO.getRunner(), userContent, ctx);

        // 6. 更新步数和统计
        updateStats(ctx, round);

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
    //  步骤方法（doApply 的每一步对应一个方法）
    // ═══════════════════════════════════════════════════════════════

    /** 获取 Agent 注册信息（含 ADK Runner 与模型配置），不存在时直接报错 */
    private AiAgentRegisterVO prepareRunner(String agentId) {
        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (registerVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }
        return registerVO;
    }

    /** 绑定终端会话到工具（session 已由 RootNode 通过 ChatService 创建/校验） */
    private void bindTerminalSession(DefaultReActFactory.DynamicContext ctx) {
        String terminalSessionId = ctx.getTerminalSessionId();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.bindSession(ctx.getSessionId(), terminalSessionId);
        }
    }

    /**
     * 意图识别——仅第 1 轮执行。
     *
     * <p>显式循环下第 2 轮起消息仍是原文，识别结果不变；且覆盖 currentIntentResult
     * 会丢掉反馈回路的重分类结果；LRU 缓存保证第 1 轮本身也不重复开销。
     * 步骤：①configure 注入模型配置 → ②classify 级联识别 → ③存入上下文 → ④不硬路由。
     */
    private void classifyIntent(AiAgentRegisterVO registerVO, DefaultReActFactory.DynamicContext ctx, String lastUserMessage) {
        // ① 复用 Agent 自己的模型配置（spring-ai 2.x 无 OpenAiApi，透传 baseUrl/apiKey/model）
        if (registerVO.getModelBaseUrl() != null && registerVO.getChatModelName() != null) {
            intentService.configure(
                    registerVO.getModelBaseUrl(), registerVO.getModelApiKey(), registerVO.getChatModelName());
        }

        // ② 级联识别（缓存 → 规则 → LLM）
        IntentResultVO intentResult = intentService.classify(ctx.getSessionId(), ctx.getUserId(), lastUserMessage);
        log.info("识别到用户意图: {}, 置信度: {}, 候选: {}, 重分类: {}",
                intentResult.getIntent().getLabel(),
                intentResult.getConfidence(),
                intentResult.getCandidateIntents(),
                intentResult.isReclassified());

        // ③ 保存到上下文供后续使用（Prompt 注入 + 反馈回路读取）
        ctx.setCurrentIntent(intentResult.getIntent().name());
        ctx.setCurrentIntentResult(intentResult);

        // ④ COMPOUND / UNKNOWN / 低置信度：交给主模型自行判断，不再硬路由
        if (intentResult.getIntent() == IntentTypeEnumVO.COMPOUND) {
            log.info("复合意图，候选 {} —— 交由主模型拆解", intentResult.getCandidateIntents());
        } else if (intentResult.getIntent() == IntentTypeEnumVO.UNKNOWN
                || intentResult.getConfidence() < 0.5) {
            log.info("意图不确定 ({}，conf={}) —— 全交主模型决策",
                    intentResult.getIntent(), intentResult.getConfidence());
        }
    }

    /**
     * 裁剪业务侧消息历史（优先级 + 滑动窗口混合策略，token 预算由配置
     * ai.session-governance.context-token-budget 决定，默认 8000）——
     * 供 ContextProvider（Task/ToolResult 等）消费；框架层历史由 CustomAdkSessionService 治理。
     */
    private void trimMessageHistory(DefaultReActFactory.DynamicContext ctx) {
        // 传 0 回退到配置默认预算（W2：预算参数化，不再在此硬编码 8000）
        List<Map<String, Object>> trimmedHistory = chatContextService.trimHistory(ctx.getMessageHistory(), 0);
        ctx.setMessageHistory(new ArrayList<>(trimmedHistory));
    }

    /**
     * 组装本轮 userContent——按轮次分发到两个构建器。
     *
     * <p>统一布局（缓存友好，教程 2-9）：稳定段在前、动态段在后：
     * <pre>
     *   第 1 轮:  原始消息 + "\n---\n" + 全量动态后缀
     *   后续轮:  原始消息 + [上一轮工具执行结果] + "\n---\n" + 增量动态段
     * </pre>
     * 动态段只影响请求末尾，system + 历史 + 稳定段前缀逐字节稳定，可命中 LLM Prompt Cache；
     * 会话净化器按同一分隔线规则剥离动态段后，落库内容与改造前逐字节一致。
     */
    private Content buildRoundUserContent(String lastUserMessage, DefaultReActFactory.DynamicContext ctx) {
        String text = ctx.getStep() == 0
                ? buildFirstRoundContent(lastUserMessage, ctx)
                : buildSubsequentRoundContent(lastUserMessage, ctx);
        return Content.fromParts(Part.fromText(text));
    }

    /** 第 1 轮：全量动态后缀（环境信息 + 最近命令 + 历史里程碑 + 当前任务 + 工具摘要 + 用户意图） */
    private String buildFirstRoundContent(String lastUserMessage, DefaultReActFactory.DynamicContext ctx) {
        String suffix = promptService.buildMessageSuffix(
                ctx.getSessionId(), ctx.getUserId(),
                ctx.getTerminalSessionId(), ctx.getRecentCommands(), ctx.getMessageHistory(),
                ctx.getCurrentIntent());
        // 更新注入水位——必须先于记录本消息里程碑：
        // 本消息的纠偏/切换应影响"下一轮"决策，而不是出现在它自己消息的动态段里
        ctx.markEnrichmentInjected(chatContextService.getToolResultCount(ctx.getSessionId()));
        // 记录用户消息里程碑（仅第 1 轮：原始消息只在循环入口出现一次，
        // 逐轮重复记录会把同一事件刷满 50 条上限，挤掉真实的工具报错事件）
        promptService.detectAndRecordMilestone(ctx.getSessionId(), "user", lastUserMessage);
        // 用户消息落库 + 偏好记忆提取（2-8）：仅首轮落库（原始消息只在循环入口出现一次）
        longTermMemoryService.saveUserMessage(
                ctx.getUserId(), ctx.getSessionId(), lastUserMessage,
                ctx.getCurrentIntent(), true);
        return suffix.isEmpty() ? lastUserMessage : lastUserMessage + "\n---\n" + suffix;
    }

    /**
     * 第 2 轮起：稳定段 = 原始消息 + 上一轮工具执行结果（回灌，消费即清空）；
     * 动态段 = 新增命令 + 新增里程碑 + 工具执行时间线（增量水位过滤）+ 意图修正提示。
     *
     * <p>工具结果块必须位于分隔线<b>之前</b>（稳定段内）：净化器按分隔线剥离动态段后，
     * 落库会话仍保留工具结果（与改造前逐字节一致），跨轮历史回放不丢执行结果。
     */
    private String buildSubsequentRoundContent(String lastUserMessage, DefaultReActFactory.DynamicContext ctx) {
        String delta = promptService.buildIncrementalSuffix(
                ctx.getSessionId(), ctx.getInjectedMilestoneTs(),
                ctx.getRecentCommands(), ctx.getInjectedCommandCount(),
                ctx.getInjectedToolResultCount());

        // 意图修正提示（反馈回路重分类后置位，一次性注入后清空，防历史中重复累积）
        String correction = ctx.getPendingIntentCorrection();
        if (correction != null && !correction.isEmpty()) {
            delta = delta.isEmpty() ? correction : delta + "\n" + correction;
            ctx.setPendingIntentCorrection("");
        }

        ctx.markEnrichmentInjected(chatContextService.getToolResultCount(ctx.getSessionId()));

        // 稳定段：原始消息 + 上一轮工具执行结果（如有）；已消费的结果即刻清空
        String stable = lastUserMessage;
        String lastResult = ctx.getLastToolResultText();
        if (lastResult != null && !lastResult.isEmpty()) {
            stable = stable + "\n\n[上一轮工具执行结果]\n" + lastResult;
        }
        ctx.setLastToolResultText("");

        return delta.isEmpty() ? stable : stable + "\n---\n" + delta;
    }

    /** 提取 userContent 纯文本长度（供日志） */
    private static int textLength(Content content) {
        return content.parts()
                .map(ps -> ps.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .reduce("", String::concat))
                .orElse("").length();
    }

    /**
     * 调用 ADK Runner 单轮生成并消费事件流；任一步骤异常都只记录错误并置位终止原因，
     * 不向上抛出（错误由 LoopDecisionNode / UserFeedbackNode 收口）。
     */
    private RoundResult invokeRunner(Runner runner, Content userContent, DefaultReActFactory.DynamicContext ctx) {
        SseEmitter emitter = ctx.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        AtomicInteger roundToolCalls = new AtomicInteger(0);

        try {
            runner.runAsync(
                    ctx.getUserId(), ctx.getSessionId(), userContent,
                    RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build()
            ).blockingForEach(event -> handleRunnerEvent(event, ctx, emitter, textAccumulator, roundToolCalls));

            log.info("ADK Runner 本轮事件流处理完成，本轮工具请求 {} 个", roundToolCalls.get());

            // 记录 assistant 消息到业务侧历史（含工具调用标记，供裁剪器分组配对）
            appendAssistantMessage(ctx, textAccumulator);

            // 助手回复落库 + 结论记忆提取（2-8）：textAccumulator 已剥离 <tool_calls> 标签，
            // 落库的是纯文本回复；旁路语义在 saveAssistantMessage 内部（失败仅 warn）
            String assistantText = textAccumulator.toString();
            if (!assistantText.isBlank()) {
                longTermMemoryService.saveAssistantMessage(
                        ctx.getUserId(), ctx.getSessionId(), assistantText);
            }
        } catch (Exception e) {
            log.error("ADK Runner 调用失败: {}", e.getMessage(), e);
            ctx.setErrorMessage("ADK Runner error: " + e.getMessage());
            ctx.setStopReason("error");
        }

        return new RoundResult(roundToolCalls.get(), textAccumulator.toString());
    }

    /** 处理单个 Runner 事件：解析工具调用标签 + 剥离标签发送 SSE 文本 */
    private void handleRunnerEvent(Event event, DefaultReActFactory.DynamicContext ctx, SseEmitter emitter,
                                   StringBuilder textAccumulator, AtomicInteger roundToolCalls) {
        // 提取模型输出（含 <tool_calls> 标签，若模型本轮请求工具）
        String raw = eventText(event);
        if (raw.isEmpty()) return;

        // 解析工具调用标签 → 注册到上下文 + 发送 SSE 事件
        for (Map<String, Object> call : parseToolCalls(raw)) {
            registerToolCall(call, ctx, emitter, roundToolCalls);
        }

        // 剥离标签后的纯文本 → SSE text（标签不展示给前端）
        // 注意：不能 trim——chunk 尾部的换行是模型的真实输出（表格/段落分隔），trim 会杀光换行
        String clean = TOOL_CALLS_BLOCK.matcher(raw).replaceAll("");
        // 再剥离 DeepSeek 思考草稿隐藏标记（草稿只随会话事件落库供下轮回传，不展示给用户）
        String display = ReasoningTagUtil.strip(clean);
        if (!display.isEmpty()) {
            textAccumulator.append(display).append("\n");
            ctx.getAssistantContent().append(display).append("\n");
            sendText(emitter, display, textAccumulator.toString(), null);
        }
    }

    /** 注册单个工具调用：分配全局唯一 id → 存入上下文 → SSE 事件 → 计数 */
    private void registerToolCall(Map<String, Object> call, DefaultReActFactory.DynamicContext ctx,
                                  SseEmitter emitter, AtomicInteger roundToolCalls) {
        String toolName = call.get("name") instanceof String s ? s : "unknown";
        @SuppressWarnings("unchecked")
        Map<String, Object> args = call.get("args") instanceof Map
                ? (Map<String, Object>) call.get("args") : Map.of();

        Map<String, Object> toolCallInfo = new HashMap<>();
        toolCallInfo.put("id", "call_" + TOOL_CALL_SEQ.incrementAndGet());
        toolCallInfo.put("name", toolName);
        toolCallInfo.put("args", args);
        ctx.getCurrentToolCalls().add(toolCallInfo);

        sendToolCallEvent(emitter, toolName, args);
        roundToolCalls.incrementAndGet();
        ctx.incrementTotalToolCalls();
        log.info("模型请求工具: {} args={}", toolName, args);
    }

    /** 将本轮 assistant 输出（含工具调用标记）写入业务侧消息历史 */
    private void appendAssistantMessage(DefaultReActFactory.DynamicContext ctx, StringBuilder textAccumulator) {
        if (textAccumulator.length() == 0 && ctx.getCurrentToolCalls().isEmpty()) {
            return;
        }
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", textAccumulator.toString());
        if (!ctx.getCurrentToolCalls().isEmpty()) {
            assistantMsg.put("tool_calls", new ArrayList<>(ctx.getCurrentToolCalls()));
        }
        ctx.appendMessage(assistantMsg);
    }

    /** 更新步数与统计 */
    private void updateStats(DefaultReActFactory.DynamicContext ctx, RoundResult round) {
        ctx.incrementStep();
        ctx.getResult().setTotalSteps(ctx.getStep());
        ctx.getResult().setTotalToolCalls(
                ctx.getResult().getTotalToolCalls() + round.roundToolCalls());

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具请求 {} 次，文本长度 {}",
                ctx.getStep(), round.roundToolCalls(), round.accumulatedText().length());
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
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

    /** 日志截断：超长消息只展示前 max 字符 */
    private static String truncateForLog(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}

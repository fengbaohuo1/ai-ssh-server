package jainingfei.site.ai.agent.cases.react.node;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.DynamicSuffixBudget;
import jainingfei.site.ai.agent.cases.react.SseSteps;
import jainingfei.site.ai.agent.cases.react.TurnCancelledException;
import jainingfei.site.ai.agent.cases.react.TurnToolSummaryCache;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 调用节点 — 回合执行（V3 托管循环，一次用户消息 = 一次 runAsync）。
 *
 * <p>工具循环不再由本节点推进：ADK Runner 内部自动执行 FunctionCall 并持续到
 * finalResponse / maxLlmCalls。本节点只做"回合前准备 + 事件中继 + 收集"：
 * <ol>
 *   <li>准备：{@link #prepareRunner} 取 Runner</li>
 *   <li>识别：{@link #classifyIntent} 意图识别（回合前，结果入会话缓存）</li>
 *   <li>同步：{@link #syncTaskStateAfterClassification} 回合入口任务态（CONTINUE 续接/业务意图
 *       重建或复用/闲聊跳过；CONTINUE 命中时回填根意图标签）</li>
 *   <li>裁剪：{@link #trimMessageHistory} 业务侧历史（供 ContextProvider 消费）</li>
 *   <li>富化：{@link #buildEntryContent} 入口消息（原始消息 + "\n---\n" + 动态后缀，
 *       2-9 缓存友好布局；意图修正提示一次性消费）</li>
 *   <li>调用：{@link #invokeRunner} 单次 runAsync，事件中继（文本分片直发 /
 *       聚合去重 / tool 步骤 / FunctionResponse 收集）</li>
 *   <li>统计与路由：更新 result；有工具结果 → ToolCallNode（副作用），无 → LoopDecisionNode</li>
 * </ol>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    /** 会话治理参数（含单次 runAsync 的 LLM 调用上限——ADK 托管循环熔断） */
    @Resource
    private SessionGovernanceProperties sessionGovernance;

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

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct AiCallNode - 开始回合执行 sessionId={}", ctx.getSessionId());

        // 1. 准备：Runner（回合内 agent 固定）
        Runner runner = prepareRunner(ctx.getAgentId());
        String message = requestParameter.getMessage() != null ? requestParameter.getMessage() : "";

        // 2. 意图识别（回合前）
        IntentResultVO intentResult = classifyIntent(ctx, message);

        // 2.5 任务态同步（回合入口：CONTINUE 续接 / 业务意图重建或复用 / CHAT·UNKNOWN 跳过）
        syncTaskStateAfterClassification(ctx, message, intentResult);

        // 3. 裁剪业务侧消息历史（供 ContextProvider 消费）
        trimMessageHistory(ctx);

        // 4. 组装入口 userContent（动态后缀在分隔线之后，稳定段在前——缓存友好）
        Content userContent = buildEntryContent(message, ctx);

        // 5. 用户消息里程碑 + 落库（每回合入口一次）
        promptService.detectAndRecordMilestone(ctx.getSessionId(), "user", message);
        longTermMemoryService.saveUserMessage(
                ctx.getUserId(), ctx.getSessionId(), message,
                ctxCurrentIntentName(ctx), true);

        // 6. 托管循环：单次 runAsync（工具执行与轮次推进由 ADK Runner 完成）
        invokeRunner(runner, userContent, ctx);

        // 7. 更新统计
        ctx.getResult().setTotalSteps(1);
        ctx.getResult().setTotalToolCalls(ctx.getToolCallCount());

        log.info("ReAct AiCallNode - 回合执行完成，工具调用 {} 次，文本长度 {}，stopReason={}",
                ctx.getToolCallCount(), ctx.getVisibleText().length(),
                ctx.getStopReason() != null ? ctx.getStopReason() : "-");

        // 8. 路由（正常无回跳：ToolCallNode 做工具副作用后经 LoopDecisionNode 收口）
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        // 异常回合且没有任何已收集的工具结果：直接进入收尾决策
        // 注意：Runner 报错前可能已经执行过工具（副作用必须落库/打点），此时仍要先经 ToolCallNode 治理，
        // 否则"执行了命令但没记账"会污染任务态与审计（P0）。
        if (ctx.getStopReason() != null && ctx.getToolResultsCollected().isEmpty()) {
            return getBean("reactLoopDecisionNode");
        }
        // 收集到工具结果 → ToolCallNode 统一执行副作用；无工具 → LoopDecisionNode 收尾
        if (!ctx.getToolResultsCollected().isEmpty()) {
            log.info("本轮执行了 {} 个工具，路由到 ToolCallNode（stopReason={}）",
                    ctx.getToolResultsCollected().size(),
                    ctx.getStopReason() != null ? ctx.getStopReason() : "-");
            return getBean("reactToolCallNode");
        }
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  步骤方法（doApply 的每一步对应一个方法）
    // ═══════════════════════════════════════════════════════════════

    /** 获取 Agent 注册信息（含 ADK Runner），不存在时报错 */
    private Runner prepareRunner(String agentId) {
        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (registerVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }
        return registerVO.getRunner();
    }

    /** 回合级意图识别（结果入会话缓存，供入口富化/工具反馈/下回合使用；异常返回 null 全交主模型） */
    private IntentResultVO classifyIntent(DefaultReActFactory.DynamicContext ctx, String message) {
        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(ctx.getAgentId());
        try {
            if (registerVO.getModelBaseUrl() != null && registerVO.getChatModelName() != null) {
                intentService.configure(registerVO.getModelProvider(),
                        registerVO.getModelBaseUrl(), registerVO.getModelApiKey(), registerVO.getChatModelName());
            }
            IntentResultVO intentResult = intentService.classify(
                    ctx.getSessionId(), ctx.getUserId(), message);
            log.info("识别到用户意图: {}, 置信度: {}, 重分类: {}",
                    intentResult.getIntent().getLabel(),
                    intentResult.getConfidence(),
                    intentResult.isReclassified());
            chatContextService.setSessionIntent(ctx.getSessionId(), intentResult);
            return intentResult;
        } catch (Exception e) {
            log.warn("意图识别失败，全交主模型决策: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 回合入口同步任务态（教学版 AiCallNode.syncTaskStateAfterClassification 的 V3.1 映射）。
     * <p>
     * 意图识别之后调用一次：CONTINUE 续接 / UNKNOWN·CHAT 跳过 / 业务意图重建或复用
     * （规则在 IntentService/ContextTracker）。CONTINUE 命中进行中任务时，把会话意图缓存
     * 回填为任务根意图——对应教学版 currentIntent = rootIntent，使 [用户意图] 标签与后续
     * 工具反馈面向根意图而非"继续"二字。
     */
    private void syncTaskStateAfterClassification(DefaultReActFactory.DynamicContext ctx, String message,
                                                  IntentResultVO intentResult) {
        if (intentResult == null) {
            return;
        }
        try {
            TaskStateVO taskState = intentService.syncTaskStateAfterClassification(
                    ctx.getSessionId(), message, intentResult);
            if (intentResult.getIntent() == IntentTypeEnumVO.CONTINUE
                    && taskState != null && !taskState.isCompleted()
                    && taskState.getRootIntent() != null) {
                // 续接：会话意图回填根意图（教学版 currentIntent=rootIntent 的映射）
                chatContextService.setSessionIntent(ctx.getSessionId(), IntentResultVO.builder()
                        .intent(taskState.getRootIntent())
                        .confidence(0.95)
                        .entities(Map.of("task", String.valueOf(taskState.getCurrentStepIndex())))
                        .candidateIntents(List.of(taskState.getRootIntent()))
                        .build());
                log.info("CONTINUE 续接任务: rootIntent={}, currentStep={}",
                        taskState.getRootIntent(), taskState.getCurrentStepIndex());
            }
        } catch (Exception e) {
            log.warn("任务态同步失败，跳过（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 裁剪业务侧历史（优先级 + 滑动窗口混合，token 预算由配置决定） */
    private void trimMessageHistory(DefaultReActFactory.DynamicContext ctx) {
        List<Map<String, Object>> trimmed = chatContextService.trimHistory(ctx.getMessageHistory(), 0);
        ctx.setMessageHistory(new ArrayList<>(trimmed));
    }

    /** 组装入口 userContent：原始消息 + "\n---\n" + 动态后缀（含一次性意图修正提示） */
    private Content buildEntryContent(String message, DefaultReActFactory.DynamicContext ctx) {
        IntentResultVO intent = chatContextService.getSessionIntent(ctx.getSessionId());
        String intentLabel = intent != null ? intent.getIntent().name() : null;

        String suffix = promptService.buildMessageSuffix(
                ctx.getSessionId(), ctx.getUserId(), ctx.getTerminalSessionId(),
                chatContextService.getRecentCommands(ctx.getSessionId()),
                ctx.getMessageHistory(), intentLabel);

        // domain 缓存在回合末必清（UserFeedbackNode.cleanup），跨回合的"上一轮工具摘要"
        // 由 case 层独立键补进来，否则 [工具执行摘要] 永远是空段
        suffix = appendPreviousToolSummary(ctx, suffix);

        String correction = chatContextService.consumePendingIntentCorrection(ctx.getSessionId());
        if (correction != null && !correction.isEmpty()) {
            suffix = suffix.isEmpty() ? correction : suffix + "\n" + correction;
        }
        // 动态后缀预算兜底：Provider 输出无上限，按节裁剪到固定预算（错误/未绑定行优先保留）
        suffix = DynamicSuffixBudget.apply(suffix);
        String entry = suffix.isEmpty() ? message : message + "\n---\n" + suffix;
        return Content.fromParts(Part.fromText(entry));
    }

    /** 把"最近一轮工具执行摘要"补进后缀（数据源：TurnToolSummaryCache，回合末由收口节点写入） */
    private String appendPreviousToolSummary(DefaultReActFactory.DynamicContext ctx, String suffix) {
        String previous = TurnToolSummaryCache.get(ctx.getSessionId());
        if (previous == null || previous.isBlank()) {
            return suffix == null ? "" : suffix;
        }
        String base = suffix == null ? "" : suffix;
        // domain Provider 若将来仍提供该段（缓存未清），避免重复注入
        if (base.contains("[工具执行摘要]")) {
            return base;
        }
        // 明确标注"上一轮"，避免模型把历史摘要当成本轮已执行的事实
        String section = "[工具执行摘要]\n（上一轮执行结果，仅供衔接上下文；本轮是否已执行以本轮工具结果为准）\n" + previous;
        return base.isEmpty() ? section : base + "\n\n" + section;
    }

    private String ctxCurrentIntentName(DefaultReActFactory.DynamicContext ctx) {
        IntentResultVO intent = chatContextService.getSessionIntent(ctx.getSessionId());
        return intent != null ? intent.getIntent().name() : null;
    }

    /**
     * 调用 ADK Runner 单次并消费事件流；任一步骤异常都只记录错误并置位终止原因
     * （错误由 ToolCallNode/LoopDecisionNode 跳过、UserFeedbackNode 收口）。
     */
    private void invokeRunner(Runner runner, Content userContent, DefaultReActFactory.DynamicContext ctx) {
        try {
            Flowable<Event> events = runner.runAsync(
                    ctx.getUserId(), ctx.getSessionId(), userContent,
                    RunConfig.builder()
                            .streamingMode(RunConfig.StreamingMode.SSE)
                            .maxLlmCalls(sessionGovernance.getLoop().getMaxLlmCalls())
                            .build());
            consumeEvents(events, ctx);

            log.info("ADK Runner 回合事件流处理完成，工具请求 {} 次", ctx.getToolCallCount());
        } catch (TurnCancelledException e) {
            // 客户端断开/超时：Runner 事件流已 dispose，本回合不再继续消耗模型与工具
            log.info("ReAct 回合已取消: {}", e.getMessage());
            ctx.setErrorMessage("本轮对话已取消: " + e.getMessage());
            ctx.setStopReason("cancelled");
            if (ctx.getResult() != null) {
                ctx.getResult().setUserStopped(true);
            }
        } catch (Exception e) {
            log.error("ADK Runner 调用失败: {}", e.getMessage(), e);
            ctx.setErrorMessage("ADK Runner error: " + e.getMessage());
            ctx.setStopReason("error");
        } finally {
            // 流结束时仍滞留的本次调用文本（无聚合事件/异常中断场景）
            flushCurrentUtterance(ctx);
        }
    }

    /**
     * 可取消地消费 Runner 事件流。
     *
     * <p>客户端断开时（心跳失败、SSE 发送失败、onTimeout/onError 回调经
     * {@link SseSteps#cancel}）取消回调会 dispose 上游订阅并唤醒这里——而不是像旧实现那样
     * 静默地跑完整个模型与工具链。事件流自然结束（onComplete）与出错（onError）走同一把锁存器，
     * 消费语义与 {@code blockingForEach} 一致（仍在订阅线程消费）。
     */
    private void consumeEvents(Flowable<Event> events, DefaultReActFactory.DynamicContext ctx)
            throws InterruptedException {
        SseEmitter emitter = ctx.getEmitter();
        if (emitter == null) {
            // 无 emitter（非流式场景）：保持原有阻塞消费语义
            events.blockingForEach(event -> handleRunnerEvent(event, ctx));
            return;
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean disposed = new AtomicBoolean(false);
        Disposable subscription = events.subscribe(
                event -> {
                    if (SseSteps.isCancelled(emitter)) {
                        throw new TurnCancelledException(SseSteps.cancellationReason(emitter));
                    }
                    handleRunnerEvent(event, ctx);
                    if (SseSteps.isCancelled(emitter)) {
                        throw new TurnCancelledException(SseSteps.cancellationReason(emitter));
                    }
                },
                error -> {
                    failure.set(error);
                    finished.countDown();
                },
                finished::countDown);

        // 取消入口：SseSteps.cancel → dispose 上游事件流，并唤醒等待线程
        Runnable cancelHook = () -> {
            if (disposed.compareAndSet(false, true)) {
                subscription.dispose();
                finished.countDown();
            }
        };
        SseSteps.setCancelHook(emitter, cancelHook);
        // 注册钩子之前可能已经收到取消信号（发送已失败的竞态）：补一次，避免 await 永久挂住
        if (SseSteps.isCancelled(emitter)) {
            cancelHook.run();
        }
        try {
            finished.await();
        } finally {
            SseSteps.clearCancelHook(emitter);
            if (disposed.compareAndSet(false, true)) {
                subscription.dispose();
            }
        }

        Throwable error = failure.get();
        if (error instanceof TurnCancelledException cancelled) {
            throw cancelled;
        }
        if (error != null) {
            throw new RuntimeException(error.getMessage(), error);
        }
        if (SseSteps.isCancelled(emitter)) {
            throw new TurnCancelledException(SseSteps.cancellationReason(emitter));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  事件中继（V3 partial/聚合语义）
    // ═══════════════════════════════════════════════════════════════

    /** 处理单个 Runner 事件：文本增量 / 工具调用步骤 / 工具结果收集 / 调用边界落段 */
    private void handleRunnerEvent(Event event, DefaultReActFactory.DynamicContext ctx) {
        // ① 文本增量：分片直发；聚合事件与分片重复时跳过（防重复渲染）
        //    累积器用"本次 LLM 调用"的缓冲（currentCallText），回合累积只追加结果——
        //    用全回合累积去比对时，第二次调用的聚合事件不构成前缀关系，差额会被丢掉
        String delta = EventTextUtil.visibleTextDelta(event, ctx.getCurrentCallText());
        if (delta != null) {
            ctx.getVisibleText().append(delta);
            ctx.getCurrentUtterance().append(delta);
            ctx.setUtteranceCharsSinceAggregated(ctx.getUtteranceCharsSinceAggregated() + delta.length());
            sendText(ctx.getEmitter(), delta);
        }

        // ② 工具调用（聚合事件携带 FunctionCall parts）→ tool_call 步骤 + 计数
        //    带 id 下发给前端：并发（批量派发/一次多命令）时结果必须按 id 配对，FIFO 会串台
        List<EventTextUtil.ToolCallView> calls = EventTextUtil.functionCalls(event);
        for (EventTextUtil.ToolCallView call : calls) {
            ctx.incrementToolCallCount();
            ctx.markStepStarted(call.id());
            log.info("模型请求工具: {} args={}", call.name(), call.args());
            sendToolCallEvent(ctx.getEmitter(), call.id(), call.name(), call.args(), null, null);
        }

        // ③ 工具执行结果（FunctionResponse parts）→ tool_result 步骤 + 收集（副作用由 ToolCallNode 统一执行）
        List<EventTextUtil.ToolResultView> results = EventTextUtil.functionResponses(event);
        for (EventTextUtil.ToolResultView result : results) {
            ctx.collectToolResult(result);
            Map<String, Object> response = result.response();
            boolean success = !Boolean.FALSE.equals(response.get("success"));
            sendToolResultEvent(ctx.getEmitter(), result.id(), result.name(), response,
                    success, ctx.takeStepElapsedMs(result.id()));
        }

        // ④⑤ 聚合事件（partial 为空）= 一次 LLM 调用结束：
        //     usage 只在这里累计（适配器只给聚合事件挂 usageMetadata），与文本/草稿的调用边界
        //     保持同一口径；随后本次调用文本转入 utterances（收口落库），并重置本调用累积器
        if (!EventTextUtil.isPartial(event)) {
            event.usageMetadata().ifPresent(ctx::accumulateUsage);
            flushCurrentUtterance(ctx);
            ctx.getCurrentCallText().setLength(0);
        }
    }

    /** 当前 LLM 调用可见文本转入 utterances（UserFeedbackNode 逐条落库 chat_message） */
    private void flushCurrentUtterance(DefaultReActFactory.DynamicContext ctx) {
        if (ctx.getUtteranceCharsSinceAggregated() <= 0) {
            return;
        }
        String text = ctx.getCurrentUtterance().toString();
        ctx.getCurrentUtterance().setLength(0);
        ctx.setUtteranceCharsSinceAggregated(0);
        if (!text.isBlank()) {
            ctx.getAssistantUtterances().add(text);
        }
    }

}

package jainingfei.site.ai.agent.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.IIntentService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.intent.IntentService;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 智能体 ReAct 执行服务（V3：ADK 托管循环 + 回合级编排）。
 *
 * <p>显式五节点循环已删除——工具循环由 ADK Runner 内部推进（模型 → FunctionCall →
 * 工具执行 → 下一轮，直到 finalResponse / maxLlmCalls）。本类只做<b>回合级</b>编排：
 * <ol>
 *   <li>会话校验/创建 + SSH 终端绑定</li>
 *   <li>意图识别（回合前）→ 会话缓存</li>
 *   <li>业务历史裁剪 + 冷启动恢复（供 ContextProvider 消费）</li>
 *   <li>入口富化：原始消息 + "\n---\n" + 动态后缀（2-9 缓存友好布局，每回合一次）</li>
 *   <li>runner.runAsync 一次（SSE + maxLlmCalls 熔断），事件中继：
 *       partial 分片文本 / 聚合事件去重 / tool_call / tool_result + 工具副作用</li>
 *   <li>收尾：done/error、清理、DB 落库（用户/工具/助手每轮文本）</li>
 * </ol>
 *
 * <p>SSE 协议与旧版逐字节兼容：{@code {"v":文本}}、{@code {"v":"","s":{...}}} 步骤、
 * 初始 thinking、心跳、流结束。
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    /** 冷启动恢复加载的历史消息条数 */
    private static final int COLD_START_HISTORY_LIMIT = 50;

    /** 每回合最多执行的 LLM 调用次数（托管循环熔断；对应 RunConfig.maxLlmCalls） */
    @Value("${ai.session-governance.loop.max-llm-calls:30}")
    private int maxLlmCalls;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private IChatService chatService;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public SseEmitter chatStream(ChatRequestDTO requestDTO) {
        // 1. 创建 SSE 发射器（3 分钟超时）
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

        // 2. 心跳保活线程（15 秒注释行，防代理超时）
        AtomicBoolean streaming = new AtomicBoolean(true);
        Thread heartbeat = startHeartbeat(emitter, streaming, requestDTO.getSessionId());
        emitter.onCompletion(() -> streaming.set(false));
        emitter.onTimeout(() -> streaming.set(false));
        emitter.onError(e -> streaming.set(false));

        // 3. 初始 thinking 事件
        try {
            emitter.send(SseEmitter.event().data("{\"v\":\"\",\"s\":{\"t\":\"thinking\"}}"));
        } catch (Exception ignored) {
        }

        // 4. 异步执行回合编排（避免阻塞 HTTP 线程）
        Thread streamThread = new Thread(() -> {
            try {
                runTurn(requestDTO, emitter);
            } catch (Exception e) {
                log.error("ReAct 托管循环回合异常", e);
                sendError(emitter, "回合执行异常: " + e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                streaming.set(false);
            }
        }, "react-turn-" + requestDTO.getSessionId());
        streamThread.setDaemon(true);
        streamThread.start();

        return emitter;
    }

    /**
     * 同步对话：阻塞执行一个托管循环回合，返回最终可见文本。
     * 用于测试和非流式场景。
     */
    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 同步对话开始 - agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
        try {
            return runTurn(requestDTO, null).visibleText();
        } catch (Exception e) {
            log.error("ReAct 同步对话异常", e);
            return "Error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  回合编排（一次用户消息 = 一次 runAsync；emitter 为 null 表示同步模式）
    // ═══════════════════════════════════════════════════════════════

    /** 回合结果（同步 chat 取 visibleText） */
    private record TurnOutcome(String visibleText, int totalToolCalls, String stopReason, String errorMessage) {
    }

    private TurnOutcome runTurn(ChatRequestDTO requestDTO, SseEmitter emitter) throws Exception {
        String userId = requestDTO.getUserId();
        String agentId = requestDTO.getAgentId();
        String terminalSessionId = requestDTO.getTerminalSessionId();
        String message = requestDTO.getMessage();
        if (message == null) {
            message = "";
        }

        // 1. Agent 注册信息（含 Runner 与模型配置）
        AiAgentRegisterVO registerVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (registerVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }
        Runner runner = registerVO.getRunner();

        // 2. 会话创建/恢复（透传客户端 sessionId，重启后旧会话可接续）
        String sessionId = chatService.createSession(agentId, userId, requestDTO.getSessionId());

        // 3. SSH 终端绑定（SshExecuteAdkTool 通过 sessionId 查 connectionId）
        boolean bound = terminalSessionId != null && !terminalSessionId.isEmpty();
        if (bound) {
            SshExecuteAdkTool.bindSession(sessionId, terminalSessionId);
        }

        // 回合级状态（展示 + 落库累积）
        StringBuilder turnText = new StringBuilder();
        StringBuilder callText = new StringBuilder();   // 当前一次 LLM 调用的可见文本
        AtomicInteger textSinceAggregated = new AtomicInteger(0);
        AtomicInteger toolCalls = new AtomicInteger(0);

        try {
            // 4. 意图识别（回合前；LRU 缓存保证同消息不重复开销）
            IntentResultVO intentResult = classifyIntent(registerVO, sessionId, userId, message);

            // 5. 业务历史：DB 冷启动恢复 → 裁剪（供 ContextProvider 消费）
            List<Map<String, Object>> messageHistory = restoreMessageHistory(sessionId);
            List<Map<String, Object>> trimmed = chatContextService.trimHistory(messageHistory, 0);

            // 6. 入口富化（每回合一次）：原始消息 + "\n---\n" + 动态后缀
            String suffix = promptService.buildMessageSuffix(
                    sessionId, userId, terminalSessionId,
                    chatContextService.getRecentCommands(sessionId), trimmed,
                    intentResult != null ? intentResult.getIntent().name() : null);
            String correction = chatContextService.consumePendingIntentCorrection(sessionId);
            if (correction != null && !correction.isEmpty()) {
                suffix = suffix.isEmpty() ? correction : suffix + "\n" + correction;
            }
            String entryText = suffix.isEmpty() ? message : message + "\n---\n" + suffix;

            // 7. 用户消息里程碑 + 落库（每回合入口一次；首轮标记沿用）
            promptService.detectAndRecordMilestone(sessionId, "user", message);
            longTermMemoryService.saveUserMessage(userId, sessionId, message,
                    intentResult != null ? intentResult.getIntent().name() : null, true);

            // 8. 托管循环：单次 runAsync，ADK 内部推进工具循环
            Content userContent = Content.fromParts(Part.fromText(entryText));
            Flowable<Event> events = runner.runAsync(userId, sessionId, userContent,
                    RunConfig.builder()
                            .streamingMode(RunConfig.StreamingMode.SSE)
                            .maxLlmCalls(maxLlmCalls)
                            .build());

            events.blockingForEach(event -> handleRunnerEvent(event, emitter, sessionId, userId,
                    turnText, callText, textSinceAggregated, toolCalls));

            // 9. 收尾落库：流结束时仍滞留的本次调用文本（无聚合事件/异常中断场景）
            flushCallText(userId, sessionId, callText, textSinceAggregated);

            String fullText = turnText.toString();
            log.info("ReAct 回合完成 - 工具调用: {} 文本长度: {}", toolCalls.get(), fullText.length());
            if (emitter != null) {
                sendStep(emitter, Map.of("t", "done"));
                emitter.complete();
            }
            return new TurnOutcome(fullText, toolCalls.get(), "completed", null);

        } catch (Exception e) {
            log.error("ReAct 回合执行失败 sessionId={}", sessionId, e);
            // 失败也保留已产生的调用文本（partial 内容已发出）
            flushCallText(userId, sessionId, callText, textSinceAggregated);
            if (emitter != null) {
                sendError(emitter, e.getMessage());
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            return new TurnOutcome(turnText.toString(), toolCalls.get(), "error", e.getMessage());
        } finally {
            cleanup(sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  事件中继（partial 去重 / tool_call / tool_result + 工具副作用）
    // ═══════════════════════════════════════════════════════════════

    private void handleRunnerEvent(Event event, SseEmitter emitter, String sessionId, String userId,
                                   StringBuilder turnText, StringBuilder callText,
                                   AtomicInteger textSinceAggregated, AtomicInteger toolCalls) {
        // ① 文本增量：分片直发；聚合事件与分片重复时跳过（防重复渲染/落库）
        String delta = EventTextUtil.visibleTextDelta(event, turnText);
        if (delta != null) {
            callText.append(delta);
            textSinceAggregated.incrementAndGet();
            if (emitter != null) {
                sendText(emitter, delta);
            }
        }

        // ② 工具调用（聚合事件携带 FunctionCall parts）→ tool_call 步骤
        List<EventTextUtil.ToolCallView> calls = EventTextUtil.functionCalls(event);
        for (EventTextUtil.ToolCallView call : calls) {
            toolCalls.incrementAndGet();
            log.info("模型请求工具: {} args={}", call.name(), call.args());
            if (emitter != null) {
                sendStep(emitter, Map.of("t", "tool_call", "n", call.name(), "a", call.args()));
            }
        }

        // ③ 工具执行结果（FunctionResponse parts）→ tool_result 步骤 + 工具副作用
        //    （记录命令/里程碑/工具结果缓存/DB 落库/意图反馈——原 ToolCallNode 职责）
        List<EventTextUtil.ToolResultView> results = EventTextUtil.functionResponses(event);
        for (EventTextUtil.ToolResultView result : results) {
            applyToolSideEffects(sessionId, userId, result);
            if (emitter != null) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("t", "tool_result");
                step.put("n", result.name());
                step.put("r", result.response());
                sendStep(emitter, step);
            }
        }

        // ④ 聚合事件（partial 为空）= 一次 LLM 调用结束 → 本次调用助手文本落库（一调用一落）
        if (!EventTextUtil.isPartial(event)) {
            flushCallText(userId, sessionId, callText, textSinceAggregated);
        }
    }

    /** 助手文本落库（当前 LLM 调用有可见文本且尚未落库时） */
    private void flushCallText(String userId, String sessionId, StringBuilder callText,
                               AtomicInteger textSinceAggregated) {
        if (textSinceAggregated.get() <= 0) {
            return;
        }
        String text = callText.toString();
        callText.setLength(0);
        textSinceAggregated.set(0);
        if (!text.isBlank()) {
            longTermMemoryService.saveAssistantMessage(userId, sessionId, text);
        }
    }

    /** 工具副作用（原 ToolCallNode 的执行后处理；FR 事件按序触发） */
    private void applyToolSideEffects(String sessionId, String userId, EventTextUtil.ToolResultView result) {
        try {
            Map<String, Object> r = result.response();
            String command = r.get("command") instanceof String s ? s : "";
            Object outputObj = r.get("output");
            String output = outputObj != null ? outputObj.toString() : "";
            boolean flaggedSuccess = Boolean.TRUE.equals(r.get("success"));
            // 旧格式：结果文本 = 输出 + [success=...] 标记（里程碑/缓存/记忆共用）
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

            // 反馈回路：工具结果 → 意图重分类（回合内更新，修正提示下回合消费）
            handleIntentFeedback(sessionId, resultText);
        } catch (Exception e) {
            log.warn("工具副作用处理失败 sessionId={} tool={}", sessionId, result.name(), e);
        }
    }

    /** 反馈回路（原 ToolCallNode.handleIntentFeedback，改用会话缓存状态） */
    private void handleIntentFeedback(String sessionId, String toolResult) {
        IntentResultVO lastIntent = chatContextService.getSessionIntent(sessionId);
        if (lastIntent == null) {
            return;
        }
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);
        IntentResultVO reclassified = intentService.reportFeedback(sessionId, lastIntent, success, toolResult);
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

    // ═══════════════════════════════════════════════════════════════
    //  回合前准备
    // ═══════════════════════════════════════════════════════════════

    /** 意图识别（回合前）：注入模型配置 → 级联识别 → 缓存会话状态 */
    private IntentResultVO classifyIntent(AiAgentRegisterVO registerVO, String sessionId,
                                          String userId, String message) {
        try {
            if (registerVO.getModelBaseUrl() != null && registerVO.getChatModelName() != null) {
                intentService.configure(
                        registerVO.getModelBaseUrl(), registerVO.getModelApiKey(), registerVO.getChatModelName());
            }
            IntentResultVO intentResult = intentService.classify(sessionId, userId, message);
            log.info("识别到用户意图: {}, 置信度: {}, 重分类: {}",
                    intentResult.getIntent().getLabel(),
                    intentResult.getConfidence(),
                    intentResult.isReclassified());
            chatContextService.setSessionIntent(sessionId, intentResult);
            return intentResult;
        } catch (Exception e) {
            // 意图识别失败不阻断主流程（2-7 级联设计的兜底语义）
            log.warn("意图识别失败，全交主模型决策: {}", e.getMessage());
            return null;
        }
    }

    /** DB 冷启动恢复：最近 50 条消息 → 业务侧历史（[{role,content,tool_call_id?,name?}]） */
    private List<Map<String, Object>> restoreMessageHistory(String sessionId) {
        List<Map<String, Object>> history = new ArrayList<>();
        try {
            List<ChatMessageEntity> recentMessages =
                    longTermMemoryService.getRecentMessages(sessionId, COLD_START_HISTORY_LIMIT);
            for (ChatMessageEntity msg : recentMessages) {
                Map<String, Object> map = new HashMap<>();
                map.put("role", msg.getRole());
                map.put("content", msg.getContent() != null ? msg.getContent() : "");
                if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                    map.put("tool_call_id", msg.getToolCallId());
                    map.put("name", msg.getToolName());
                }
                history.add(map);
            }
            if (!recentMessages.isEmpty()) {
                log.info("冷启动恢复: 加载 {} 条历史消息 sessionId={}", recentMessages.size(), sessionId);
            }
        } catch (Exception e) {
            log.warn("冷启动恢复失败，从空历史开始 sessionId={}", sessionId, e);
        }
        return history;
    }

    /** 回合结束清理（终端解绑 + 工具结果缓存清理，防旧结果污染下一回合） */
    private void cleanup(String sessionId) {
        try {
            if (sessionId != null) {
                SshExecuteAdkTool.unbindSession(sessionId);
                chatContextService.clearSessionContext(sessionId);
            }
        } catch (Exception e) {
            log.warn("回合清理异常 sessionId={}", sessionId, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 发送（协议与旧版一致）
    // ═══════════════════════════════════════════════════════════════

    private void sendText(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .data("{\"v\":" + MAPPER.writeValueAsString(content) + "}"));
        } catch (Exception e) {
            log.warn("SSE text 发送失败: {}", e.getMessage());
        }
    }

    private void sendStep(SseEmitter emitter, Map<String, Object> step) {
        try {
            emitter.send(SseEmitter.event()
                    .data("{\"v\":\"\",\"s\":" + MAPPER.writeValueAsString(step) + "}"));
        } catch (Exception e) {
            log.warn("SSE step 发送失败: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String errorMsg) {
        try {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("t", "error");
            step.put("m", errorMsg);
            emitter.send(SseEmitter.event()
                    .data("{\"v\":\"\",\"s\":" + MAPPER.writeValueAsString(step) + "}"));
        } catch (Exception ignored) {
        }
    }

    /** 心跳线程（15 秒注释行，防代理超时断开） */
    private Thread startHeartbeat(SseEmitter emitter, AtomicBoolean streaming, String label) {
        Thread t = new Thread(() -> {
            try {
                while (streaming.get()) {
                    Thread.sleep(15000);
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (Exception e) {
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "sse-heartbeat-" + label);
        t.setDaemon(true);
        t.start();
        return t;
    }
}

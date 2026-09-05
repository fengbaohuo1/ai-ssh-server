package jainingfei.site.ai.agent.domain.agent.service.context;

import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.PromptContextVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import jainingfei.site.ai.agent.domain.agent.service.context.provider.impl.ToolResultProvider;
import jainingfei.site.ai.agent.domain.agent.service.context.reducer.impl.HybridReducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文管理领域服务实现（context 包的聚合核心）。
 *
 * <p>整体运行过程（一次显式 ReAct 循环中的调用时序）：
 * <pre>
 *   AiCallNode.doApply()
 *        |
 *        | (1) trimHistory(history, 预算=0回退默认8000，可配置)
 *        |        |
 *        |        v
 *        |     HybridReducer = PriorityReducer ∪ SlidingWindowReducer + 保底最近2组
 *        |        |
 *        |        v
 *        |     裁剪后的历史回写 DynamicContext
 *        |
 *        | (2) PromptService.buildMessageSuffix(...)
 *        |        |
 *        |        v
 *        |     buildPromptContext(sessionId, userId, terminalSessionId, history)
 *        |        |
 *        |        +--&gt; for provider in providers(按order排序, 跳过disabled):
 *        |        |        TerminalState(10)  {osInfo, currentUser, currentDirectory}
 *        |        |        Task(20)           {taskDescription}
 *        |        |        Milestone(30)      {milestoneVOS}
 *        |        |        ToolResult(40)     {toolResultSummary}
 *        |        |     finalCtx.putAll(...)   合并所有键值对
 *        |        |
 *        |        v
 *        |     PromptContextVO --&gt; DynamicPromptBuilder --&gt; 消息后缀（拼在用户消息之后，缓存友好）
 *        |
 *        | (3) 工具执行后 pushToolResult(sessionId, toolName, result)
 *        |        |
 *        |        v
 *        |     ToolResultProvider.pushResult() 缓存 + 使摘要失效
 *        |     （下一轮 (2) 时新摘要进入 Prompt）
 * </pre>
 *
 * <p>装配机制：{@code @Resource List<ContextProvider>} 由 Spring 按类型自动收集，
 * 构造时按 getOrder() 排序——新增 Provider 只需加 @Component，无需修改本类。
 */
@Service
public class ChatContextService implements IChatContextService {

    private final List<ContextProvider> providers;

    /** 业务侧历史裁剪预算（HybridReducer），默认 8000；可经 ai.session-governance.context-token-budget 覆盖 */
    @Value("${ai.session-governance.context-token-budget:8000}")
    private int contextTokenBudget;

    @Resource
    private HybridReducer hybridReducer;

    @Resource
    private ToolResultProvider toolResultProvider;

    public ChatContextService(List<ContextProvider> providers) {
        this.providers = providers;
        this.providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId,
                                              List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) {
                continue;
            }
            Map<String, Object> ctx = provider.provide(sessionId, userId, terminalSessionId, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .build();
    }

    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : contextTokenBudget);
    }

    @Override
    public void pushToolResult(String sessionId, String toolName, String command, boolean success, String result) {
        toolResultProvider.pushResult(sessionId, toolName, command, success, result);
    }

    @Override
    public void clearSessionContext(String sessionId) {
        if (sessionId != null) {
            toolResultProvider.clear(sessionId);
        }
    }

    @Override
    public int getToolResultCount(String sessionId) {
        return sessionId == null ? 0 : toolResultProvider.count(sessionId);
    }

    @Override
    public List<String> getToolResultTimeline(String sessionId, int fromIndex) {
        return sessionId == null ? List.of() : toolResultProvider.timelineSince(sessionId, fromIndex);
    }

    // ═══════════════════════════════════════════════════════════════
    //  会话级运行状态（V3 托管循环）
    // ═══════════════════════════════════════════════════════════════

    /** 最近执行命令缓存：sessionId → FIFO 队列（上限 20） */
    private static final int MAX_RECENT_COMMANDS = 20;
    private final java.util.Map<String, java.util.ArrayDeque<String>> recentCommands = new java.util.concurrent.ConcurrentHashMap<>();
    /** 会话当前意图 */
    private final java.util.Map<String, IntentResultVO> sessionIntents = new java.util.concurrent.ConcurrentHashMap<>();
    /** 会话待注入意图修正提示（下回合消费） */
    private final java.util.Map<String, String> pendingCorrections = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void pushRecentCommand(String sessionId, String command) {
        if (sessionId == null || command == null || command.trim().isEmpty()) {
            return;
        }
        java.util.ArrayDeque<String> queue = recentCommands.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= MAX_RECENT_COMMANDS) {
                queue.removeFirst();
            }
            queue.addLast(command.trim());
        }
    }

    @Override
    public List<String> getRecentCommands(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        java.util.ArrayDeque<String> queue = recentCommands.get(sessionId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    @Override
    public void setSessionIntent(String sessionId, IntentResultVO intent) {
        if (sessionId != null) {
            sessionIntents.put(sessionId, intent);
        }
    }

    @Override
    public IntentResultVO getSessionIntent(String sessionId) {
        return sessionId == null ? null : sessionIntents.get(sessionId);
    }

    @Override
    public void setPendingIntentCorrection(String sessionId, String correction) {
        if (sessionId != null && correction != null && !correction.isEmpty()) {
            pendingCorrections.put(sessionId, correction);
        }
    }

    @Override
    public String consumePendingIntentCorrection(String sessionId) {
        return sessionId == null ? null : pendingCorrections.remove(sessionId);
    }
}

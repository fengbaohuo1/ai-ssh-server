package jainingfei.site.ai.agent.domain.agent.support;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派发预算——给"派发子智能体"这件事装上刹车。
 *
 * <p>为什么需要：hybrid 模式下父智能体既能自己执行、也能派发，模型很容易退化成
 * "遇到稍微复杂的检查就拆四个子任务"，既烧 token 又容易套娃（子智能体再派发子智能体）。
 * 提示词只能引导，运行时必须有硬约束：
 * <pre>
 *   ① 每轮对话最多 {@value #MAX_DISPATCH_CALLS_PER_TURN} 次派发调用（dispatchSubAgents / 单个子智能体 / 规划派发都算）
 *   ② 每轮最多 {@value #MAX_CHILD_TASKS_PER_TURN} 个子任务
 *   ③ 同一个子智能体每轮最多 {@value #MAX_TASKS_PER_AGENT_PER_TURN} 个任务
 *   ④ 同一轮内"子智能体 + 任务描述"去重，禁止把同一件事派两遍
 *   ⑤ 子智能体会话（前缀 {@value #CHILD_SESSION_PREFIX}）禁止再派发——从根上断掉套娃
 * </pre>
 *
 * <p>为什么是静态类：预算的消费者是"工具"（每次装配都会 new 一批），而轮次边界在
 * {@code ChatService} 里——两者只共享 sessionId，没有可注入的公共 bean。
 * 与 {@link SshSessionBindings} 同一套思路：跨层协议放 support 包，键是 ADK 会话 ID。
 *
 * <p>轮次开始由 {@code ChatService} 调 {@link #beginTurn(String)} 重置；重置失败（如热重装后
 * 会话 ID 变化）退化为"上一轮预算继续累计"，最坏情况是多拦几次派发，不会放开。
 */
@Slf4j
public final class DispatchBudget {

    /** 每轮对话允许的派发调用次数 */
    public static final int MAX_DISPATCH_CALLS_PER_TURN = 2;

    /** 每轮对话允许创建的子任务总数 */
    public static final int MAX_CHILD_TASKS_PER_TURN = 6;

    /** 同一子智能体每轮允许的任务数 */
    public static final int MAX_TASKS_PER_AGENT_PER_TURN = 4;

    /** 子智能体会话 ID 前缀（SubAgentDispatchService 创建子会话时使用） */
    public static final String CHILD_SESSION_PREFIX = "subagent-";

    /** sessionId → 本轮预算 */
    private static final Map<String, Budget> BUDGETS = new ConcurrentHashMap<>();

    private DispatchBudget() {
    }

    /** 开始新一轮对话：重置该会话的派发预算（在 ChatService 收到请求时调用） */
    public static void beginTurn(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            BUDGETS.put(sessionId, new Budget());
        }
    }

    /** 会话结束后清理（可按会话 ID 释放；定时清理不做，避免引入调度依赖） */
    public static void clear(String sessionId) {
        if (sessionId != null) {
            BUDGETS.remove(sessionId);
        }
    }

    /** 是否是子智能体会话（子智能体不允许再派发） */
    public static boolean isChildSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(CHILD_SESSION_PREFIX);
    }

    /**
     * 子智能体会话的派发拦截：命中返回给模型看的错误文案，未命中返回 {@code null}。
     */
    public static String rejectChildSessionDispatch(String sessionId) {
        if (isChildSession(sessionId)) {
            return "子智能体禁止再派发子智能体（已检测到嵌套派发）。请直接使用自己的工具完成任务，或把结论返回给父智能体。";
        }
        return null;
    }

    /**
     * 占用一次派发调用额度。
     *
     * @return 超限时返回给模型看的错误文案；否则 {@code null}
     */
    public static String reserveDispatchCall(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Budget budget = BUDGETS.computeIfAbsent(sessionId, key -> new Budget());
        synchronized (budget) {
            if (budget.calls >= MAX_DISPATCH_CALLS_PER_TURN) {
                log.info("派发预算拦截 | sessionId:{} | calls:{} | 已达上限", sessionId, budget.calls);
                return "本轮已派发 " + budget.calls + " 次子智能体（上限 " + MAX_DISPATCH_CALLS_PER_TURN
                        + " 次）。剩下的工作请自己用 executeCommand 等工具完成，或直接汇总已有结果。";
            }
            budget.calls++;
            return null;
        }
    }

    /**
     * 占用一个子任务额度（含去重与按智能体限额）。
     *
     * @return 超限/重复时返回给模型看的错误文案；否则 {@code null}
     */
    public static String reserveTask(String sessionId, String agentName, String request) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Budget budget = BUDGETS.computeIfAbsent(sessionId, key -> new Budget());
        String fingerprint = fingerprint(agentName, request);
        String agent = agentName == null || agentName.isBlank() ? "unknown" : agentName;
        synchronized (budget) {
            if (budget.tasks >= MAX_CHILD_TASKS_PER_TURN) {
                log.info("派发预算拦截 | sessionId:{} | tasks:{} | 已达子任务上限", sessionId, budget.tasks);
                return "本轮子任务数已达上限（" + MAX_CHILD_TASKS_PER_TURN + " 个）。请把剩余检查合并成少量命令自己执行。";
            }
            int agentTasks = budget.tasksPerAgent.getOrDefault(agent, 0);
            if (agentTasks >= MAX_TASKS_PER_AGENT_PER_TURN) {
                log.info("派发预算拦截 | sessionId:{} | agent:{} | 同智能体任务已达上限", sessionId, agent);
                return "子智能体 " + agent + " 本轮已接 " + agentTasks + " 个任务（上限 "
                        + MAX_TASKS_PER_AGENT_PER_TURN + "）。请合并剩余任务或自己执行。";
            }
            if (fingerprint != null && !budget.fingerprints.add(fingerprint)) {
                log.info("派发预算拦截 | sessionId:{} | agent:{} | 重复任务", sessionId, agent);
                return "同一个任务已经派发给 " + agent + " 了，不要重复派发；请直接使用上一次的结果。";
            }
            budget.tasks++;
            budget.tasksPerAgent.put(agent, agentTasks + 1);
            return null;
        }
    }

    /** 任务指纹：子智能体名 + 归一化任务描述（去重只在本轮内生效） */
    private static String fingerprint(String agentName, String request) {
        if (request == null) {
            return null;
        }
        String normalized = request.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return (agentName == null ? "unknown" : agentName) + "|" + normalized.hashCode();
    }

    /** 单个会话的预算账本 */
    private static final class Budget {
        private int calls;
        private int tasks;
        private final Map<String, Integer> tasksPerAgent = new HashMap<>();
        private final Set<String> fingerprints = new HashSet<>();
    }
}

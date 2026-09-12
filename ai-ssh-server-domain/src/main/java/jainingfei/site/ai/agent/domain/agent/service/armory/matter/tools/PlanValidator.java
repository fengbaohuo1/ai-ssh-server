package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务计划校验器——LLM 动态规划与确定性执行器之间的"防呆 + 防失控"防火墙（教程 2-10）。
 *
 * <p>LLM 输出不可信：可能为空、任务数量超限、ID 重复、引用不存在的依赖、甚至环依赖。
 * 这些脏数据如果直接进编排器，轻则任务分派错乱，重则调度循环永远凑不出就绪任务而卡死。
 *
 * <p>规则（快速失败：低成本检查在前，环检测最后）：
 * <ol>
 *   <li>计划非空、任务数 ≤ maxTasks（默认 10）：防止一次规划出 50 个任务把模型 API 与 SSH 打爆</li>
 *   <li>taskId 非空且唯一（重复 ID 会让依赖指向歧义）</li>
 *   <li>软约束静默修正：{@code maxRetries} 钳制 ≤3、{@code maxConcurrency} 钳制 [1,8]
 *       ——小事直接修，不必让模型重规划整轮</li>
 *   <li>依赖必须存在（悬空依赖会让下游永远等不到上游完成）</li>
 *   <li>依赖不能成环（三色标记 DFS）——虽然编排器有"无就绪任务即退出"的兜底，
 *       但校验层提前拒绝能给出明确错误信息，模型可据此外修正计划</li>
 * </ol>
 */
@Slf4j
@Service
public class PlanValidator {

    /** 单任务重试次数上限（LLM 可能规划出失控的重试策略） */
    private static final int MAX_RETRIES_LIMIT = 3;

    /** 并发上限（防止一次规划把模型 API 与 SSH 连接同时打满） */
    private static final int MAX_CONCURRENCY_LIMIT = 8;

    /** 默认并发 */
    private static final int DEFAULT_CONCURRENCY = 4;

    /**
     * 执行完整校验；不满足硬约束时抛 {@link IllegalArgumentException}，
     * 由派发工具统一兜底成 {@code {success:false, error}} 返回给模型。
     *
     * @param plan     待校验计划
     * @param maxTasks 任务数量上限
     */
    public void validate(DynamicTaskPlan plan, int maxTasks) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            throw new IllegalArgumentException("task plan is empty");
        }
        if (plan.getTasks().size() > maxTasks) {
            throw new IllegalArgumentException("too many tasks: " + plan.getTasks().size());
        }

        Set<String> taskIds = new HashSet<>();
        for (var task : plan.getTasks()) {
            if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                throw new IllegalArgumentException("task id is blank");
            }
            if (!taskIds.add(task.getTaskId())) {
                throw new IllegalArgumentException("duplicate task id: " + task.getTaskId());
            }
            if (task.getDependsOn() == null) {
                task.setDependsOn(new ArrayList<>());
            }
            // 软约束：静默钳制重试次数
            if (task.getMaxRetries() != null && task.getMaxRetries() > MAX_RETRIES_LIMIT) {
                log.info("任务重试次数超限，钳制为 {} | task={} | 原值={}",
                        MAX_RETRIES_LIMIT, task.getTaskId(), task.getMaxRetries());
                task.setMaxRetries(MAX_RETRIES_LIMIT);
            }
            if (task.getMaxRetries() != null && task.getMaxRetries() < 0) {
                task.setMaxRetries(0);
            }
        }

        for (var task : plan.getTasks()) {
            for (String dependency : task.getDependsOn()) {
                if (!taskIds.contains(dependency)) {
                    throw new IllegalArgumentException("unknown dependency: " + dependency);
                }
            }
        }

        if (hasCycle(plan)) {
            throw new IllegalArgumentException("task dependency cycle");
        }

        // 软约束：并发钳制到 [1, MAX_CONCURRENCY_LIMIT]
        int concurrency = plan.getMaxConcurrency() == null ? DEFAULT_CONCURRENCY : plan.getMaxConcurrency();
        plan.setMaxConcurrency(Math.max(1, Math.min(MAX_CONCURRENCY_LIMIT, concurrency)));
    }

    /** 遍历所有任务做环检测（任意一个成环即 true），visited 剪枝，整体 O(V+E) */
    private boolean hasCycle(DynamicTaskPlan plan) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (var task : plan.getTasks()) {
            if (hasCycle(task.getTaskId(), task.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 三色标记 DFS 环检测：
     * <pre>
     *   visiting（灰）= 当前递归路径上的节点 —— 再次遇到即为回边 → 有环
     *   visited （黑）= 已彻底检测完且无环的节点 —— 直接剪枝跳过
     * </pre>
     * 只用一个 visited 集合无法区分"在当前路径上（成环）"与"已检测过（安全）"两种情况，
     * 这正是三色标记法存在的原因（JVM 可达性分析、死锁检测同款思路）。
     */
    private boolean hasCycle(String taskId, List<String> dependencies, DynamicTaskPlan plan,
                             Set<String> visiting, Set<String> visited) {
        if (visiting.contains(taskId)) {
            return true;
        }
        if (visited.contains(taskId)) {
            return false;
        }
        visiting.add(taskId);

        for (String dependency : dependencies) {
            var parent = plan.getTasks().stream()
                    .filter(item -> item.getTaskId().equals(dependency))
                    .findFirst()
                    .orElse(null);
            if (parent != null && hasCycle(dependency, parent.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        visiting.remove(taskId);
        visited.add(taskId);
        return false;
    }
}

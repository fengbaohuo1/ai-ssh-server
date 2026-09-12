package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * DAG 编排器——按任务依赖关系调度一批子智能体任务（教程 2-10 的"调度心脏"）。
 *
 * <p><b>轮次扫描</b>而不是一次性拓扑排序：每一轮把"依赖全部 COMPLETED"的任务凑成一批并发跑，
 * 跑完再扫下一轮。实现简单，且天然同时覆盖两种形态——互相无依赖的任务同批并行、
 * 依赖前一批的任务下一轮串行。
 *
 * <p><b>并发限流</b>：任务数可以很多，但真正同时在跑的子智能体不超过 maxConcurrency
 * （Semaphore 卡住），避免一次规划出 10 个任务就把模型 API 与 SSH 连接打爆。
 *
 * <p><b>失败语义</b>：单任务异常只影响该任务；其<b>下游</b>任务被显式置为 SKIPPED 并带原因
 * （沿依赖链逐轮级联），无依赖分支照常跑完。计划开启 failFast 时，首个失败后的下一轮不再调度，
 * 剩余 PENDING 全部 SKIPPED——对应"前置检查失败就不该继续安装"的变更链路。
 */
@Slf4j
@Service
public class DynamicAgentOrchestrator {

    private final SubAgentDispatchService dispatchService;
    private final Executor executor;

    public DynamicAgentOrchestrator(SubAgentDispatchService dispatchService,
                                    @Qualifier("threadPoolExecutor") Executor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    /**
     * 执行整个计划（阻塞到全部任务到达终态）。
     *
     * @param context 派发上下文（appName / 父智能体名 / 终端会话）
     * @param plan    已通过 {@link PlanValidator} 校验的计划
     * @return {plan, tasks, allSucceeded, failedCount, skippedCount}
     */
    public Map<String, Object> execute(AgentExecutionContext context, DynamicTaskPlan plan) {
        // taskId → task（LinkedHashMap 保序，日志与返回结果的可读性更稳）
        Map<String, DynamicTask> tasks = new LinkedHashMap<>();
        plan.getTasks().forEach(task -> tasks.put(task.getTaskId(), task));

        int concurrencyLimit = Math.max(1, plan.getMaxConcurrency() == null ? 4 : plan.getMaxConcurrency());
        Semaphore concurrency = new Semaphore(concurrencyLimit);
        int round = 0;

        while (tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.PENDING)) {
            round++;

            // ① failFast：已有任务失败 → 不再调度新任务，剩余 PENDING 全部 SKIPPED
            if (Boolean.TRUE.equals(plan.getFailFast())
                    && tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
                tasks.values().stream()
                        .filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .forEach(task -> {
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setError("skipped by failFast: another task failed");
                        });
                log.warn("DAG 调度中止（failFast）：剩余任务全部跳过 | appName:{}", context.getAppName());
                break;
            }

            // ② 级联跳过：依赖了 FAILED/SKIPPED 任务的 PENDING 任务显式置 SKIPPED（逐轮沿链传播）
            for (DynamicTask task : tasks.values()) {
                if (task.getStatus() != TaskStatus.PENDING) {
                    continue;
                }
                boolean blocked = task.getDependsOn().stream()
                        .map(tasks::get)
                        .anyMatch(parent -> parent == null
                                || parent.getStatus() == TaskStatus.FAILED
                                || parent.getStatus() == TaskStatus.SKIPPED);
                if (blocked) {
                    task.setStatus(TaskStatus.SKIPPED);
                    task.setError("skipped: dependency failed or skipped");
                }
            }

            // ③ 就绪任务：PENDING 且依赖全部 COMPLETED
            List<DynamicTask> readyTasks = tasks.values().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(tasks::get)
                            .allMatch(parent -> parent != null && parent.getStatus() == TaskStatus.COMPLETED))
                    .toList();

            // ④ 兜底：没有就绪任务（校验器已排除环，正常不会走到）→ 提前退出，避免死循环
            if (readyTasks.isEmpty()) {
                log.warn("DAG 调度无可执行任务，提前退出 | appName:{} | round:{}", context.getAppName(), round);
                break;
            }

            log.info("DAG 调度第{}轮 | ready:[{}] | 并发上限:{} | appName:{}",
                    round, readyTasks.stream().map(DynamicTask::getTaskId).collect(Collectors.joining(",")),
                    concurrencyLimit, context.getAppName());

            // ⑤ 并发执行本轮就绪任务（每轮一个屏障：下一轮依赖本轮全部完成）
            List<CompletableFuture<Void>> futures = readyTasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {
                        boolean acquired = false;
                        try {
                            concurrency.acquire();
                            acquired = true;
                            dispatchService.execute(context, task);
                        } catch (Exception exception) {
                            task.setStatus(TaskStatus.FAILED);
                            task.setError(exception.getMessage());
                            log.error("子任务执行异常 | task={} | agent={}", task.getTaskId(), task.getAgentName(), exception);
                        } finally {
                            if (acquired) {
                                concurrency.release();
                            }
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("plan", plan);
        response.put("tasks", new ArrayList<>(tasks.values()));
        response.put("allSucceeded", tasks.values().stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED));
        response.put("failedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.FAILED).count());
        response.put("skippedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.SKIPPED).count());
        log.info("DAG 调度结束 | rounds:{} | allSucceeded:{} | failed:{} | skipped:{}",
                round, response.get("allSucceeded"), response.get("failedCount"), response.get("skippedCount"));
        return response;
    }
}

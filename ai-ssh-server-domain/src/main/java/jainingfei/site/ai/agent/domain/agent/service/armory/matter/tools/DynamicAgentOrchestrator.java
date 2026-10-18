package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.support.AgentTaskContextPropagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *
 * <p><b>健壮性（本轮加固）</b>：
 * <ul>
 *   <li><b>信号量在提交前获取</b>：许可拿不到就不提交，任务体里不再有"排队等许可"的中间态；
 *       提交失败（{@link RejectedExecutionException}）时归还许可并标记 FAILED，杜绝"拒绝后永远不执行"的孤儿任务</li>
 *   <li><b>超时真正生效</b>：每个 future 按任务自己的 timeoutSeconds 等待（缺省 120s），
 *       超时标记 FAILED 并 cancel；每轮再有一道"总超时"（各任务超时之和 + 余量）兜住 join</li>
 *   <li><b>上下文传播</b>：任务体经 {@link AgentTaskContextPropagator#wrap} 包装，子智能体执行期
 *       能读到父线程的审计 userId / connectionId 与命令熔断作用域</li>
 * </ul>
 */
@Slf4j
@Service
public class DynamicAgentOrchestrator {

    /** 任务未显式配置超时时的默认值（与派发服务默认值保持一致） */
    private static final int DEFAULT_TASK_TIMEOUT_SECONDS = 120;

    /** 每轮 join 总超时的额外余量（调度、事件上报、线程切换的开销） */
    private static final long ROUND_TIMEOUT_MARGIN_MILLIS = 5_000L;

    private final SubAgentDispatchService dispatchService;
    private final Executor executor;

    public DynamicAgentOrchestrator(SubAgentDispatchService dispatchService,
                                    @Qualifier("threadPoolExecutor") Executor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    /**
     * 执行整个计划（阻塞到全部任务到达终态，或本轮总超时耗尽）。
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
        // 已判定超时的任务 → 超时原因：任务体被 cancel 后仍可能在后台跑完，收尾时以超时结论为准
        Map<String, String> timeoutErrors = new ConcurrentHashMap<>();
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
            List<TaskFuture> submitted = submitRound(context, readyTasks, concurrency, timeoutErrors);
            awaitRound(context, round, submitted, timeoutErrors);
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

    /**
     * 提交本轮任务：<b>先拿信号量许可、再提交</b>。
     *
     * <p>顺序反过来的坏处：任务先进队列，线程池拒绝（AbortPolicy）时异常在提交处抛出，
     * 但任务在 LLM 眼里仍是 PENDING，而 join 永远等不到它——这正是"孤儿任务"。
     * 现在许可先到手，提交被拒绝也能归还许可并显式标记 FAILED。
     */
    private List<TaskFuture> submitRound(AgentExecutionContext context, List<DynamicTask> readyTasks,
                                         Semaphore concurrency, Map<String, String> timeoutErrors) {
        List<TaskFuture> submitted = new ArrayList<>(readyTasks.size());
        for (DynamicTask task : readyTasks) {
            long timeoutMillis = taskTimeoutMillis(task);
            try {
                concurrency.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                task.setStatus(TaskStatus.FAILED);
                task.setError("interrupted while waiting for concurrency permit");
                log.warn("DAG 等待并发许可被中断，本轮不再提交 | task={} | agent={}",
                        task.getTaskId(), task.getAgentName());
                break;
            }

            CompletableFuture<Void> future;
            try {
                future = CompletableFuture.runAsync(
                        // 线程池边界：传播父线程审计上下文 + 本次派发的 userId/connectionId/作用域
                        AgentTaskContextPropagator.wrap(
                                () -> runTask(context, task, concurrency, timeoutErrors),
                                context, task.getAgentName()),
                        executor);
            } catch (RejectedExecutionException rejected) {
                concurrency.release(); // 没提交成功，归还许可
                task.setStatus(TaskStatus.FAILED);
                task.setError("rejected by executor: " + rejected.getMessage());
                log.error("DAG 任务被线程池拒绝，已标记失败（不再静默遗漏） | task={} | agent={}",
                        task.getTaskId(), task.getAgentName(), rejected);
                continue;
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            submitted.add(new TaskFuture(task, future, timeoutMillis, deadlineNanos));
        }
        return submitted;
    }

    /** 任务体：执行 + 异常兜底；无论成败都归还信号量许可（超时取消后也不会泄漏许可） */
    private void runTask(AgentExecutionContext context, DynamicTask task,
                         Semaphore concurrency, Map<String, String> timeoutErrors) {
        try {
            dispatchService.execute(context, task);
            if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.RUNNING) {
                // 执行器没给终态：不能让任务永远停在 RUNNING，否则轮次扫描会误判为"没跑完"
                task.setStatus(TaskStatus.FAILED);
                task.setError("task finished without terminal status");
                log.warn("子任务缺少终态，按失败处理 | task={} | agent={}", task.getTaskId(), task.getAgentName());
            }
        } catch (Exception exception) {
            task.setStatus(TaskStatus.FAILED);
            task.setError(exception.getMessage());
            log.error("子任务执行异常 | task={} | agent={}", task.getTaskId(), task.getAgentName(), exception);
        } finally {
            // 超时取消后任务体可能仍在后台跑完：收尾时以超时结论为准，避免把 FAILED 覆盖回 COMPLETED
            String timeoutError = timeoutErrors.get(task.getTaskId());
            if (timeoutError != null) {
                task.setStatus(TaskStatus.FAILED);
                task.setError(timeoutError);
            }
            concurrency.release();
        }
    }

    /**
     * 等待本轮全部任务结束：单任务超时按 timeoutSeconds，整体 join 再有一道总超时。
     *
     * <p>超时路径都会 cancel 对应 future 并标记 FAILED，且都打日志——不再有"无限等"。
     */
    private void awaitRound(AgentExecutionContext context, int round, List<TaskFuture> submitted,
                            Map<String, String> timeoutErrors) {
        if (submitted.isEmpty()) {
            return;
        }
        long roundBudgetMillis = ROUND_TIMEOUT_MARGIN_MILLIS
                + submitted.stream().mapToLong(TaskFuture::timeoutMillis).sum();
        long roundDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(roundBudgetMillis);

        for (int index = 0; index < submitted.size(); index++) {
            TaskFuture entry = submitted.get(index);
            long now = System.nanoTime();
            long remainingTask = entry.deadlineNanos() - now;
            long remainingRound = roundDeadline - now;

            if (remainingTask <= 0) {
                failByTimeout(context, entry, timeoutErrors, "子任务执行超时");
                continue;
            }
            if (remainingRound <= 0) {
                cancelRemaining(context, submitted, index, timeoutErrors,
                        "本轮等待超过总超时（" + roundBudgetMillis + "ms）");
                break;
            }

            long waitNanos = Math.min(remainingTask, remainingRound);
            try {
                entry.future().get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timedOut) {
                if (remainingTask <= remainingRound) {
                    failByTimeout(context, entry, timeoutErrors, "子任务执行超时");
                } else {
                    cancelRemaining(context, submitted, index, timeoutErrors,
                            "本轮等待超过总超时（" + roundBudgetMillis + "ms）");
                    break;
                }
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                entry.task().setStatus(TaskStatus.FAILED);
                entry.task().setError("execution failed: " + cause.getMessage());
                log.error("子任务执行失败 | task={} | agent={}", entry.task().getTaskId(),
                        entry.task().getAgentName(), cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("DAG 等待任务完成被中断，取消剩余任务 | round={} | appName={}", round, context.getAppName());
                cancelRemaining(context, submitted, index, timeoutErrors, "orchestrator interrupted");
                break;
            }
        }
    }

    /** 单任务超时：cancel future + 标记 FAILED + 记日志（日志里带超时秒数与出错连接） */
    private void failByTimeout(AgentExecutionContext context, TaskFuture entry,
                               Map<String, String> timeoutErrors, String reason) {
        if (entry.future().isDone()) {
            // 恰好在截止时刻完成：已完成的任务不推翻结论（可能已 COMPLETED / FAILED）
            return;
        }
        String error = reason + " (timeout=" + entry.timeoutMillis() + "ms)";
        entry.future().cancel(true);
        timeoutErrors.put(entry.task().getTaskId(), error);
        entry.task().setStatus(TaskStatus.FAILED);
        entry.task().setError(error);
        log.warn("子任务执行超时，已取消 | task={} | agent={} | timeout={}ms | appName={}",
                entry.task().getTaskId(), entry.task().getAgentName(), entry.timeoutMillis(), context.getAppName());
    }

    /** 整体总超时 / 线程中断：从 index 起（含）取消剩余 future 并统一标记失败 */
    private void cancelRemaining(AgentExecutionContext context, List<TaskFuture> submitted, int index,
                                 Map<String, String> timeoutErrors, String reason) {
        for (int cursor = index; cursor < submitted.size(); cursor++) {
            TaskFuture entry = submitted.get(cursor);
            if (entry.future().isDone()) {
                continue; // 已完成的任务保持自己的结论
            }
            String error = reason + " (timeout=" + entry.timeoutMillis() + "ms)";
            entry.future().cancel(true);
            timeoutErrors.put(entry.task().getTaskId(), error);
            entry.task().setStatus(TaskStatus.FAILED);
            entry.task().setError(error);
            log.warn("已取消未完成任务 | task={} | agent={} | 原因={} | appName={}",
                    entry.task().getTaskId(), entry.task().getAgentName(), reason, context.getAppName());
        }
    }

    /** 单任务超时（毫秒）：优先用任务自己的 timeoutSeconds，缺省沿用 120s */
    private static long taskTimeoutMillis(DynamicTask task) {
        Integer seconds = task.getTimeoutSeconds();
        int effective = seconds == null || seconds <= 0 ? DEFAULT_TASK_TIMEOUT_SECONDS : seconds;
        return effective * 1000L;
    }

    /** 已提交任务与它的等待截止时间 */
    private record TaskFuture(DynamicTask task, CompletableFuture<Void> future,
                              long timeoutMillis, long deadlineNanos) {
    }
}

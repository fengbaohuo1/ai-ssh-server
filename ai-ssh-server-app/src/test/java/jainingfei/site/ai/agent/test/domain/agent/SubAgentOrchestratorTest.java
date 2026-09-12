package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.TaskStatus;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.DynamicAgentOrchestrator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 编排器（DynamicAgentOrchestrator）单元测试——用假执行器验证调度语义（不接模型不联网）。
 *
 * <p>覆盖：无依赖并行全部成功、依赖链顺序（依赖必须先 COMPLETED）、中层失败级联 SKIPPED、
 * 多级级联、failFast 中止、failFast 关闭时分支隔离、并发上限真正生效。
 */
public class SubAgentOrchestratorTest {

    /** 假执行器：记录调用顺序、依赖运行时的上游状态、并发峰值，可按 taskId 编程失败 */
    static class FakeDispatchService extends SubAgentDispatchService {
        final Set<String> failing = ConcurrentHashMap.newKeySet();
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        final List<String> finishedOrder = Collections.synchronizedList(new ArrayList<>());
        final List<String> dependencySnapshot = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        volatile DynamicTaskPlan plan;
        long perTaskMillis = 0;

        FakeDispatchService() {
            super(null, null, null);
        }

        @Override
        public void execute(AgentExecutionContext context, DynamicTask task) {
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                calls.merge(task.getTaskId(), 1, Integer::sum);
                if (plan != null) {
                    for (String dependency : task.getDependsOn()) {
                        DynamicTask parent = plan.getTasks().stream()
                                .filter(item -> item.getTaskId().equals(dependency))
                                .findFirst().orElse(null);
                        dependencySnapshot.add(task.getTaskId() + "<-" + dependency + ":"
                                + (parent == null ? "null" : parent.getStatus()));
                    }
                }
                if (perTaskMillis > 0) {
                    Thread.sleep(perTaskMillis);
                }
                finishedOrder.add(task.getTaskId());
                if (failing.contains(task.getTaskId())) {
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("boom: " + task.getTaskId());
                } else {
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setResult("ok:" + task.getTaskId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }
    }

    private DynamicTask task(String id, String... dependsOn) {
        DynamicTask task = new DynamicTask();
        task.setTaskId(id);
        task.setAgentName("sshDiagnosisAgent");
        task.setRequest("检查 " + id);
        task.setDependsOn(new ArrayList<>(List.of(dependsOn)));
        return task;
    }

    private Map<String, Object> run(FakeDispatchService fake, DynamicTaskPlan plan) {
        fake.plan = plan;
        DynamicAgentOrchestrator orchestrator = new DynamicAgentOrchestrator(fake, Executors.newFixedThreadPool(8));
        return orchestrator.execute(AgentExecutionContext.builder()
                .userId("u1").appName("sshAgent").agentName("sshOperator").build(), plan);
    }

    private TaskStatus statusOf(Map<String, Object> result, String taskId) {
        @SuppressWarnings("unchecked")
        List<DynamicTask> tasks = (List<DynamicTask>) result.get("tasks");
        return tasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst().orElseThrow().getStatus();
    }

    private String errorOf(Map<String, Object> result, String taskId) {
        @SuppressWarnings("unchecked")
        List<DynamicTask> tasks = (List<DynamicTask>) result.get("tasks");
        return tasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst().orElseThrow().getError();
    }

    @Test
    public void test_all_independent_tasks_succeed() {
        FakeDispatchService fake = new FakeDispatchService();
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("cpu"), task("mem"), task("disk")))
                .maxConcurrency(4)
                .build();

        Map<String, Object> result = run(fake, plan);

        assertTrue((Boolean) result.get("allSucceeded"));
        assertEquals(0L, result.get("failedCount"));
        assertEquals(0L, result.get("skippedCount"));
        assertEquals(Set.of("cpu", "mem", "disk"), Set.copyOf(fake.finishedOrder));
    }

    @Test
    public void test_dependency_chain_runs_in_order() {
        FakeDispatchService fake = new FakeDispatchService();
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("diag"), task("fix", "diag")))
                .maxConcurrency(4)
                .build();

        run(fake, plan);

        // 下游执行时上游必须已经 COMPLETED（轮次扫描的核心保证）
        assertTrue(fake.dependencySnapshot.contains("fix<-diag:COMPLETED"), fake.dependencySnapshot.toString());
        assertEquals(1, fake.calls.get("diag"));
        assertEquals(1, fake.calls.get("fix"));
        assertTrue(fake.finishedOrder.indexOf("diag") < fake.finishedOrder.indexOf("fix"));
    }

    @Test
    public void test_middle_failure_cascades_skip_but_other_branch_continues() {
        FakeDispatchService fake = new FakeDispatchService();
        fake.failing.add("b");
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b", "a"), task("c", "b"), task("d")))
                .maxConcurrency(4)
                .build();

        Map<String, Object> result = run(fake, plan);

        assertEquals(TaskStatus.COMPLETED, statusOf(result, "a"));
        assertEquals(TaskStatus.FAILED, statusOf(result, "b"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "c"));
        assertEquals(TaskStatus.COMPLETED, statusOf(result, "d"), "无依赖分支不受影响");
        assertTrue(errorOf(result, "c").contains("dependency failed or skipped"), errorOf(result, "c"));
        assertFalse(fake.calls.containsKey("c"), "被跳过的任务不应真的执行");
        assertFalse((Boolean) result.get("allSucceeded"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(1L, result.get("skippedCount"));
    }

    @Test
    public void test_multi_level_cascade() {
        FakeDispatchService fake = new FakeDispatchService();
        fake.failing.add("t1");
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("t1"), task("t2", "t1"), task("t3", "t2"), task("t4", "t3")))
                .maxConcurrency(4)
                .build();

        Map<String, Object> result = run(fake, plan);

        assertEquals(TaskStatus.FAILED, statusOf(result, "t1"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "t2"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "t3"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "t4"));
        assertEquals(3L, result.get("skippedCount"));
    }

    @Test
    public void test_fail_fast_skips_remaining_pending() {
        FakeDispatchService fake = new FakeDispatchService();
        fake.failing.add("a");
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b", "a"), task("c", "b")))
                .maxConcurrency(1)
                .failFast(true)
                .build();

        Map<String, Object> result = run(fake, plan);

        assertEquals(TaskStatus.FAILED, statusOf(result, "a"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "b"));
        assertEquals(TaskStatus.SKIPPED, statusOf(result, "c"));
        assertTrue(errorOf(result, "b").contains("failFast"), errorOf(result, "b"));
        assertFalse(fake.calls.containsKey("c"));
    }

    @Test
    public void test_max_concurrency_limit_is_enforced() {
        FakeDispatchService fake = new FakeDispatchService();
        fake.perTaskMillis = 30;
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("t1"), task("t2"), task("t3"), task("t4")))
                .maxConcurrency(1)
                .build();

        run(fake, plan);

        assertEquals(1, fake.maxRunning.get(), "maxConcurrency=1 应退化为串行");
    }
}

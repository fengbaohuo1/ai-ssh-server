package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务计划校验（PlanValidator）单元测试——纯函数。
 *
 * <p>覆盖：合法计划通过、空计划、数量超限、ID 重复、悬空依赖、环检测（含自依赖）、
 * 重试次数钳制、并发钳制（软约束静默修正）。
 */
public class SubAgentPlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    private DynamicTask task(String id, String... dependsOn) {
        DynamicTask task = new DynamicTask();
        task.setTaskId(id);
        task.setAgentName("sshDiagnosisAgent");
        task.setRequest("检查 " + id);
        task.setDependsOn(new ArrayList<>(List.of(dependsOn)));
        return task;
    }

    private DynamicTaskPlan plan(DynamicTask... tasks) {
        return DynamicTaskPlan.builder()
                .tasks(List.of(tasks))
                .maxConcurrency(4)
                .failFast(false)
                .build();
    }

    @Test
    public void test_valid_plan_with_dependency_passes() {
        DynamicTaskPlan plan = plan(task("check"), task("fix", "check"));
        validator.validate(plan, 10);
        assertEquals(4, plan.getMaxConcurrency());
    }

    @Test
    public void test_empty_plan_rejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(DynamicTaskPlan.builder().tasks(List.of()).build(), 10));
    }

    @Test
    public void test_too_many_tasks_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(plan(task("t1"), task("t2")), 1));
        assertTrue(ex.getMessage().contains("too many tasks"), ex.getMessage());
    }

    @Test
    public void test_duplicate_task_id_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(plan(task("t1"), task("t1")), 10));
        assertTrue(ex.getMessage().contains("duplicate task id"), ex.getMessage());
    }

    @Test
    public void test_unknown_dependency_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(plan(task("t1", "ghost")), 10));
        assertTrue(ex.getMessage().contains("unknown dependency"), ex.getMessage());
    }

    @Test
    public void test_dependency_cycle_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(plan(task("a", "b"), task("b", "a")), 10));
        assertTrue(ex.getMessage().contains("cycle"), ex.getMessage());
    }

    @Test
    public void test_self_dependency_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(plan(task("a", "a")), 10));
        assertTrue(ex.getMessage().contains("cycle"), ex.getMessage());
    }

    @Test
    public void test_max_retries_clamped_silently() {
        DynamicTask runaway = task("t1");
        runaway.setMaxRetries(100);
        DynamicTask reasonable = task("t2");
        reasonable.setMaxRetries(2);
        validator.validate(plan(runaway, reasonable), 10);
        assertEquals(3, runaway.getMaxRetries(), "超过上限应静默钳制为 3");
        assertEquals(2, reasonable.getMaxRetries(), "未超限不应改动");
    }

    @Test
    public void test_max_concurrency_clamped() {
        DynamicTaskPlan tooMany = DynamicTaskPlan.builder().tasks(List.of(task("t1"))).maxConcurrency(99).build();
        validator.validate(tooMany, 10);
        assertEquals(8, tooMany.getMaxConcurrency());

        DynamicTaskPlan tooFew = DynamicTaskPlan.builder().tasks(List.of(task("t1"))).maxConcurrency(0).build();
        validator.validate(tooFew, 10);
        assertEquals(1, tooFew.getMaxConcurrency());
    }
}

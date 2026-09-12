package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划计划解析（PlanParser）单元测试——纯函数，无网络无 Spring。
 *
 * <p>覆盖：Markdown 包裹抢救、缺 taskId 补全、白名单校验、空 request、默认值、
 * 数字字段容错（"4"/4.0）、垃圾输出。
 */
public class SubAgentPlanParserTest {

    private final PlanParser parser = new PlanParser();
    private final List<String> allowed = List.of("sshDiagnosisAgent", "sshChangeAgent");

    @Test
    public void test_parse_json_wrapped_by_markdown() {
        String output = """
                好的，计划如下：
                ```json
                {"tasks":[{"taskId":"t1","agentName":"sshDiagnosisAgent","request":"查磁盘"}],"maxConcurrency":2}
                ```
                请执行。
                """;
        DynamicTaskPlan plan = parser.parse(output, allowed);
        assertEquals(1, plan.getTasks().size());
        assertEquals(2, plan.getMaxConcurrency());
        assertEquals("t1", plan.getTasks().get(0).getTaskId());
        assertEquals("sshDiagnosisAgent", plan.getTasks().get(0).getAgentName());
        assertEquals("查磁盘", plan.getTasks().get(0).getRequest());
    }

    @Test
    public void test_unknown_agent_rejected() {
        String output = "{\"tasks\":[{\"taskId\":\"t1\",\"agentName\":\"hackerAgent\",\"request\":\"rm -rf /\"}]}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(output, allowed));
        assertTrue(ex.getMessage().contains("agent not allowed"), ex.getMessage());
    }

    @Test
    public void test_missing_task_id_backfilled() {
        String output = "{\"tasks\":[{\"agentName\":\"sshChangeAgent\",\"request\":\"安装 nginx\"}]}";
        DynamicTaskPlan plan = parser.parse(output, allowed);
        assertTrue(plan.getTasks().get(0).getTaskId().startsWith("task-"));
    }

    @Test
    public void test_blank_request_rejected() {
        String output = "{\"tasks\":[{\"taskId\":\"t1\",\"agentName\":\"sshChangeAgent\",\"request\":\"   \"}]}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(output, allowed));
        assertTrue(ex.getMessage().contains("request is blank"), ex.getMessage());
    }

    @Test
    public void test_parse_fail_fast_and_task_fields() {
        String output = """
                {"tasks":[{"taskId":"diag","agentName":"sshDiagnosisAgent","request":"查状态","timeoutSeconds":60,"maxRetries":2},
                          {"taskId":"fix","agentName":"sshChangeAgent","request":"重启服务","dependsOn":["diag"]}],
                 "maxConcurrency":3,"failFast":true}
                """;
        DynamicTaskPlan plan = parser.parse(output, allowed);
        assertEquals(2, plan.getTasks().size());
        assertTrue(plan.getFailFast());
        assertEquals(3, plan.getMaxConcurrency());
        DynamicTask diag = plan.getTasks().get(0);
        assertEquals(60, diag.getTimeoutSeconds());
        assertEquals(2, diag.getMaxRetries());
        assertEquals(List.of("diag"), plan.getTasks().get(1).getDependsOn());
    }

    @Test
    public void test_defaults_when_fields_absent() {
        String output = "{\"tasks\":[{\"taskId\":\"t1\",\"agentName\":\"sshDiagnosisAgent\",\"request\":\"查状态\"}]}";
        DynamicTaskPlan plan = parser.parse(output, allowed);
        DynamicTask task = plan.getTasks().get(0);
        assertFalse(plan.getFailFast());
        assertEquals(4, plan.getMaxConcurrency());
        assertEquals(120, task.getTimeoutSeconds());
        assertEquals(0, task.getMaxRetries());
    }

    @Test
    public void test_numeric_string_fields_tolerated() {
        // 模型常把数字写成字符串或小数——按 Number/字符串容错，不整份计划判死
        String output = "{\"tasks\":[{\"taskId\":\"t1\",\"agentName\":\"sshDiagnosisAgent\",\"request\":\"查状态\","
                + "\"timeoutSeconds\":\"60\",\"maxRetries\":2.0}],\"maxConcurrency\":\"2\"}";
        DynamicTaskPlan plan = parser.parse(output, allowed);
        assertEquals(2, plan.getMaxConcurrency());
        assertEquals(60, plan.getTasks().get(0).getTimeoutSeconds());
        assertEquals(2, plan.getTasks().get(0).getMaxRetries());
    }

    @Test
    public void test_garbage_output_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("抱歉，我无法完成这个任务。", allowed));
        assertTrue(ex.getMessage().contains("invalid agent plan"), ex.getMessage());
    }

    @Test
    public void test_empty_tasks_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"tasks\":[]}", allowed));
        assertTrue(ex.getMessage().contains("tasks is empty"), ex.getMessage());
    }
}

package jainingfei.site.ai.agent.test.cases.react;

import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 回合内步骤记账（DynamicContext）单元测试。
 *
 * <p>覆盖：步骤开始/耗时配对、回合收口 drain 悬挂步骤（真实测试里出现过"tool_call 没有
 * tool_result"，前端卡片永远 running，需要收口时补发失败态）、重复 drain 幂等、空 id 忽略。
 */
public class ReActStepBookkeepingTest {

    @Test
    public void test_take_elapsed_pairs_and_clears() {
        DefaultReActFactory.DynamicContext ctx = DefaultReActFactory.DynamicContext.builder().build();
        ctx.markStepStarted("call-1", "executeCommand");

        Long elapsed = ctx.takeStepElapsedMs("call-1");
        assertNotNull(elapsed, "首次取耗时应有记录");
        assertTrue(elapsed >= 0, "耗时不能为负");
        assertNull(ctx.takeStepElapsedMs("call-1"), "取过之后记录应被清理");
        assertTrue(ctx.drainUnfinishedSteps().isEmpty(), "已配对步骤不应再被 drain");
    }

    @Test
    public void test_drain_returns_unfinished_steps_with_names() {
        DefaultReActFactory.DynamicContext ctx = DefaultReActFactory.DynamicContext.builder().build();
        ctx.markStepStarted("call-1", "executeCommand");
        ctx.takeStepElapsedMs("call-1");
        ctx.markStepStarted("call-2", "readRemoteFile");
        ctx.markStepStarted("call-3", "listRemoteFiles");

        List<DefaultReActFactory.DynamicContext.UnfinishedStep> dangling = ctx.drainUnfinishedSteps();
        assertEquals(2, dangling.size(), "只应包含没有结果事件的步骤");
        Set<String> names = new HashSet<>();
        for (DefaultReActFactory.DynamicContext.UnfinishedStep step : dangling) {
            names.add(step.name());
            assertTrue(step.elapsedMs() >= 0);
            assertNotNull(step.id());
        }
        assertTrue(names.contains("readRemoteFile"), names.toString());
        assertTrue(names.contains("listRemoteFiles"), names.toString());

        assertTrue(ctx.drainUnfinishedSteps().isEmpty(), "drain 应幂等，第二次为空");
    }

    @Test
    public void test_blank_step_id_and_name_are_ignored() {
        DefaultReActFactory.DynamicContext ctx = DefaultReActFactory.DynamicContext.builder().build();
        ctx.markStepStarted(null, "executeCommand");
        ctx.markStepStarted("", "executeCommand");
        assertTrue(ctx.drainUnfinishedSteps().isEmpty());

        // 有 id 没名字：补发时用 unknown 兜底，不能因名字缺失丢步骤
        ctx.markStepStarted("call-x", null);
        List<DefaultReActFactory.DynamicContext.UnfinishedStep> dangling = ctx.drainUnfinishedSteps();
        assertEquals(1, dangling.size());
        assertEquals("unknown", dangling.get(0).name());
    }

    @Test
    public void test_remark_after_drain_restarts_timer() {
        DefaultReActFactory.DynamicContext ctx = DefaultReActFactory.DynamicContext.builder().build();
        ctx.markStepStarted("call-1", "executeCommand");
        Long first = ctx.drainUnfinishedSteps().get(0).elapsedMs();
        ctx.markStepStarted("call-1", "executeCommand");
        Long second = ctx.drainUnfinishedSteps().get(0).elapsedMs();
        assertTrue(second >= first, "重新 mark 应是新一次记录，耗时不应小于上次");
    }
}

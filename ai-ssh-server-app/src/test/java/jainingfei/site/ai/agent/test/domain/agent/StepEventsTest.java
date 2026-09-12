package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.cases.react.SseSteps;
import jainingfei.site.ai.agent.domain.agent.support.StepEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 步骤协议 v2 的事件形状单元测试——纯数据，无网络无 Spring。
 *
 * <p>固定住三件前端赖以工作的约定：
 * <pre>
 *   ① 步骤必须带 id/st：前端按 id 幂等 upsert（并发时 FIFO 配对会串台）
 *   ② 父子关系用 p：子智能体的命令挂在派发卡片下面（缺失即顶层）
 *   ③ 空字段不下发：没有 id/p/k/ms 时不得出现 null 值，否则前端要额外判空
 * </pre>
 */
public class StepEventsTest {

    @Test
    @DisplayName("tool_call 步骤：带 id/状态，父与种类为空时不出现该字段")
    public void test_tool_call_shape() {
        Map<String, Object> step = StepEvents.toolCall("call-1", "executeCommand",
                Map.of("command", "uname -a"), null, null);

        String json = SseSteps.json(step);
        assertTrue(json.contains("\"t\":\"tool_call\""), json);
        assertTrue(json.contains("\"id\":\"call-1\""), json);
        assertTrue(json.contains("\"st\":\"running\""), json);
        assertTrue(json.contains("\"n\":\"executeCommand\""), json);
        assertTrue(json.contains("uname -a"), json);
        assertFalse(json.contains("\"p\""), "无父步骤时不得下发 p：" + json);
        assertFalse(json.contains("\"k\""), "无种类时不得下发 k：" + json);
    }

    @Test
    @DisplayName("嵌套步骤：p（父步骤 id）、k（种类）、ag（所属子智能体）齐全")
    public void test_nested_step_shape() {
        Map<String, Object> child = StepEvents.with(
                StepEvents.toolCall("call-2", "executeCommand", Map.of("command", "df -h"),
                        StepEvents.K_TOOL, "task-1"),
                StepEvents.F_AGENT, "sshDiagnosisAgent");

        String json = SseSteps.json(child);
        assertTrue(json.contains("\"p\":\"task-1\""), json);
        assertTrue(json.contains("\"k\":\"tool\""), json);
        assertTrue(json.contains("\"ag\":\"sshDiagnosisAgent\""), json);
    }

    @Test
    @DisplayName("tool_result 步骤：st 由成功与否推导，耗时随终态下发")
    public void test_tool_result_shape() {
        String ok = SseSteps.json(StepEvents.toolResult("call-1", "executeCommand",
                Map.of("success", true, "output", "Linux"), true, 1250L));
        assertTrue(ok.contains("\"t\":\"tool_result\""), ok);
        assertTrue(ok.contains("\"st\":\"ok\""), ok);
        assertTrue(ok.contains("\"ms\":1250"), ok);

        String failed = SseSteps.json(StepEvents.toolResult("call-2", "sshChangeAgent",
                Map.of("success", false, "error", "命令执行异常"), false, null));
        assertTrue(failed.contains("\"st\":\"error\""), failed);
        assertFalse(failed.contains("\"ms\""), "耗时未知时不下发 ms：" + failed);
    }

    @Test
    @DisplayName("done 带 stopReason：前端据此区分正常结束与中断")
    public void test_done_shape() {
        assertTrue(SseSteps.json(StepEvents.done("completed")).contains("\"sr\":\"completed\""));
        assertTrue(SseSteps.json(StepEvents.done("error")).contains("\"sr\":\"error\""));
        assertFalse(SseSteps.json(StepEvents.done(null)).contains("\"sr\""), "无原因时不下发 sr");
    }

    @Test
    @DisplayName("error 带人类可读原因；usage 带四项 token 计数")
    public void test_error_and_usage_shape() {
        String error = SseSteps.json(StepEvents.error("回合执行失败: timeout"));
        assertTrue(error.contains("\"t\":\"error\""), error);
        assertTrue(error.contains("\"m\":\"回合执行失败: timeout\""), error);

        String usage = SseSteps.json(StepEvents.usage(1200, 300, 1500, 1024));
        assertTrue(usage.contains("\"t\":\"usage\""), usage);
        assertTrue(usage.contains("\"pt\":1200"), usage);
        assertTrue(usage.contains("\"ct\":300"), usage);
        assertTrue(usage.contains("\"tt\":1500"), usage);
        assertTrue(usage.contains("\"cached\":1024"), usage);
    }

    @Test
    @DisplayName("信封：步骤事件 v 为空、文本事件 s 缺席（前端两种都认）")
    public void test_envelopes() {
        String stepPayload = SseSteps.stepPayload(StepEvents.thinking());
        assertTrue(stepPayload.startsWith("{\"v\":\"\",\"s\":{"), stepPayload);
        assertTrue(stepPayload.contains("\"t\":\"thinking\""), stepPayload);

        String textPayload = SseSteps.textPayload("你好");
        assertTrue(textPayload.startsWith("{\"v\":\"你好\""), textPayload);
        assertFalse(textPayload.contains("\"s\""), textPayload);
    }

    @Test
    @DisplayName("序列化失败不抛给调用方（观测能力不能打断回合）")
    public void test_json_never_throws() {
        // 自引用对象会让 Jackson 抛 Infinite recursion → 必须退化成空对象而不是把异常抛出去
        Object cyclic = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };
        assertEquals("{}", SseSteps.json(cyclic));
    }
}

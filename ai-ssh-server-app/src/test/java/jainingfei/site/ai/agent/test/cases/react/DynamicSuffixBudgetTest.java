package jainingfei.site.ai.agent.test.cases.react;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jainingfei.site.ai.agent.cases.react.DynamicSuffixBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DynamicSuffixBudget} 单元测试：只验证"预算兜底不改语义"的几条硬规则。
 */
class DynamicSuffixBudgetTest {

    @Test
    @DisplayName("null/空后缀返回空串")
    void test_null_or_empty() {
        assertEquals("", DynamicSuffixBudget.apply(null));
        assertEquals("", DynamicSuffixBudget.apply(""));
    }

    @Test
    @DisplayName("未超预算时逐字节原样返回（缓存前缀稳定）")
    void test_passthrough_within_budget() {
        String suffix = "[系统环境]\nlinux\n\n[用户意图]\n查看磁盘";
        assertEquals(suffix, DynamicSuffixBudget.apply(suffix));

        // 恰好等于预算边界也不应被裁剪
        String exact = "x".repeat(DynamicSuffixBudget.MAX_SUFFIX_CHARS);
        assertEquals(exact, DynamicSuffixBudget.apply(exact));
    }

    @Test
    @DisplayName("总长超预算时结果不超过上限")
    void test_over_budget_result_bounded() {
        String suffix = "[最近执行的命令]\n" + "ls -la /var/log\n".repeat(500)
                + "\n[用户意图]\n排查磁盘";
        String result = DynamicSuffixBudget.apply(suffix);
        assertTrue(result.length() <= DynamicSuffixBudget.MAX_SUFFIX_CHARS,
                "result length=" + result.length());
        assertTrue(result.contains("[用户意图]"), "用户意图节不应被丢弃");
    }

    @Test
    @DisplayName("关键错误行即使所在节被省略也会被抢救到末尾")
    void test_critical_lines_rescued() {
        StringBuilder sb = new StringBuilder();
        sb.append("[最近执行的命令]\n");
        for (int i = 0; i < 300; i++) {
            sb.append("cat /proc/cpuinfo | head -1\n");
        }
        sb.append("executeCommand 失败: SSH 会话未绑定\n");
        sb.append("\n[用户意图]\n重新采集状态");
        String result = DynamicSuffixBudget.apply(sb.toString());

        assertTrue(result.length() <= DynamicSuffixBudget.MAX_SUFFIX_CHARS);
        assertTrue(result.contains("[关键错误信息]"), "应出现抢救段标题");
        assertTrue(result.contains("未绑定"), "关键错误行必须保留");
    }

    @Test
    @DisplayName("单节正文超限时按节裁剪并标注标记")
    void test_section_body_trimmed() {
        String suffix = "[系统环境]\n" + "cpu: 8 core\n".repeat(400)
                + "\n[用户意图]\n继续";
        String result = DynamicSuffixBudget.apply(suffix);
        assertTrue(result.length() <= DynamicSuffixBudget.MAX_SUFFIX_CHARS);
        assertTrue(result.contains("[系统环境]") || result.contains("[动态上下文已裁剪"));
    }

    @Test
    @DisplayName("无已知节标题的超长文本走硬截断")
    void test_no_known_headers_hard_truncate() {
        String suffix = "y".repeat(DynamicSuffixBudget.MAX_SUFFIX_CHARS * 2);
        String result = DynamicSuffixBudget.apply(suffix);
        assertTrue(result.length() <= DynamicSuffixBudget.MAX_SUFFIX_CHARS);
    }

    @Test
    @DisplayName("正文中的方括号文本不会被当成节标题")
    void test_unknown_brackets_not_headers() {
        String suffix = "[runtime]\njava 17\n[notes] keep me";
        assertEquals(suffix, DynamicSuffixBudget.apply(suffix));
    }
}

package jainingfei.site.ai.agent.test.domain.agent.tool;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.CommandOutcomeAnalyzer;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令结果分析（CommandOutcomeAnalyzer）单元测试——纯文本启发式，无状态无网络。
 *
 * <p>它只给模型提供线索（"这次可能是失败了，原因大概是…"），不作为系统决策依据；
 * 因此这里固定的是"词表命中 → 归类话术"的映射，以及中文输出的已知盲区。
 */
public class CommandOutcomeAnalyzerTest {

    @Test
    @DisplayName("isSuccessful：null / 空输出视为成功（无输出不等于失败）")
    public void test_empty_output_is_success() {
        assertTrue(CommandOutcomeAnalyzer.isSuccessful((String) null));
        assertTrue(CommandOutcomeAnalyzer.isSuccessful(""));
    }

    @Test
    @DisplayName("isSuccessful：正常回显视为成功")
    public void test_normal_output_is_success() {
        assertTrue(CommandOutcomeAnalyzer.isSuccessful("total 0\ndrwxr-xr-x 2 root root 6 Jan 1 00:00 ."));
        assertTrue(CommandOutcomeAnalyzer.isSuccessful("Filesystem      Size  Used Avail Use% Mounted on"));
        assertTrue(CommandOutcomeAnalyzer.isSuccessful("nginx is running"));
    }

    @Test
    @DisplayName("isSuccessful：命中失败信号词即为失败，且大小写不敏感")
    public void test_failure_signals_detected() {
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("bash: foo: command not found"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("cat: /root/x: Permission denied"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("dd: failed to open '/dev/sda'"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("fatal: not a git repository"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("curl: (7) Failed to connect"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("ssh: connect to host x port 22: Connection refused"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("ERROR: something went wrong"));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("Unable to locate package nginx"));
    }

    @Test
    @DisplayName("analyzeError：按信号词归类失败原因")
    public void test_analyze_error_classification() {
        assertTrue(CommandOutcomeAnalyzer.analyzeError("bash: nginx: command not found").contains("命令不存在"));
        assertTrue(CommandOutcomeAnalyzer.analyzeError("rm: cannot remove: Permission denied").contains("权限不足"));
        assertTrue(CommandOutcomeAnalyzer.analyzeError("cat: a.log: No such file or directory").contains("文件或目录不存在"));
        assertTrue(CommandOutcomeAnalyzer.analyzeError("curl: (7) Connection refused").contains("网络连接问题"));
        assertTrue(CommandOutcomeAnalyzer.analyzeError("ping: Network is unreachable").contains("网络连接问题"));
    }

    @Test
    @DisplayName("analyzeError：无法归类时给出通用提示；null 输入返回 null")
    public void test_analyze_error_fallback() {
        assertEquals("执行失败，请检查命令和输出信息。", CommandOutcomeAnalyzer.analyzeError("something odd happened"));
        assertNull(CommandOutcomeAnalyzer.analyzeError((String) null));
    }

    @Test
    @DisplayName("analyzeError：归类优先级——同时出现多个信号词时按代码顺序取第一个")
    public void test_analyze_error_priority() {
        // "command not found" 在 permission denied 之前判定，命中前者
        String output = "bash: x: command not found\nsome: Permission denied";
        assertTrue(CommandOutcomeAnalyzer.analyzeError(output).contains("命令不存在"));
    }

    @Test
    @DisplayName("中文错误输出：归类仍可用，但英文信号词缺失时 isSuccessful 会漏判为成功（已知盲区）")
    public void test_chinese_output_limitation() {
        // analyzeError 对无法识别的内容给通用提示，不会误判成成功
        assertEquals("执行失败，请检查命令和输出信息。", CommandOutcomeAnalyzer.analyzeError("错误: 无法连接到服务器"));
        // 但 isSuccessful 的词表全部是英文信号词：纯中文报错不会被识别为失败。
        // 这是设计上的保守取舍（只做线索，不做决策），此处固定为已知盲区。
        assertTrue(CommandOutcomeAnalyzer.isSuccessful("错误: 无法连接到服务器"),
                "已知盲区：纯中文报错不在英文信号词表内，会被判为成功");
        // 中英混排的常见形态则能被识别
        assertFalse(CommandOutcomeAnalyzer.isSuccessful("错误: command not found"));
    }

    // ==================== 结构化结果（退出码为准） ====================

    @Test
    @DisplayName("结构化：退出码 0 → 成功；退出码非 0 → 失败（即使输出为空/无报错词）")
    public void test_structured_exit_status_decides() {
        assertTrue(CommandOutcomeAnalyzer.isSuccessful(
                SshCommandResult.builder().stdout("ok").exitStatus(0).build()));
        // 关键回归：`false` 命令无任何输出，旧文本判据会误判成功
        assertFalse(CommandOutcomeAnalyzer.isSuccessful(
                SshCommandResult.builder().stdout("").exitStatus(1).build()));
        // 有正常输出但退出码非 0（如 grep 只匹配到部分）也应以退出码为准
        assertFalse(CommandOutcomeAnalyzer.isSuccessful(
                SshCommandResult.builder().stdout("partial line").exitStatus(2).build()));
    }

    @Test
    @DisplayName("结构化：超时 / 输出截断 → 失败，且错误建议可区分")
    public void test_structured_timeout_and_truncated() {
        SshCommandResult timeout = SshCommandResult.builder()
                .stdout("partial").exitStatus(null).timedOut(true).build();
        assertFalse(CommandOutcomeAnalyzer.isSuccessful(timeout));
        assertTrue(CommandOutcomeAnalyzer.analyzeError(timeout).contains("超时"));

        SshCommandResult truncated = SshCommandResult.builder()
                .stdout("head...").exitStatus(0).truncated(true).build();
        assertFalse(CommandOutcomeAnalyzer.isSuccessful(truncated));
        assertTrue(CommandOutcomeAnalyzer.analyzeError(truncated).contains("截断"));
    }

    @Test
    @DisplayName("结构化：无退出码时退化为文本启发式（兼容旧通道）")
    public void test_structured_falls_back_to_text() {
        assertTrue(CommandOutcomeAnalyzer.isSuccessful(
                SshCommandResult.builder().stdout("total 0").build()));
        assertFalse(CommandOutcomeAnalyzer.isSuccessful(
                SshCommandResult.builder().stdout("bash: x: command not found").build()));
    }

    @Test
    @DisplayName("结构化：stderr 并入 combinedOutput，供模型与审计看到")
    public void test_structured_combined_output() {
        SshCommandResult r = SshCommandResult.builder().stdout("out").stderr("err").exitStatus(1).build();
        assertTrue(r.combinedOutput().contains("out"));
        assertTrue(r.combinedOutput().contains("[stderr]"));
        assertTrue(r.combinedOutput().contains("err"));
        SshCommandResult onlyErr = SshCommandResult.builder().stdout("").stderr("boom").build();
        assertTrue(onlyErr.combinedOutput().contains("boom"));
        assertFalse(onlyErr.hasExitStatus());
    }

    @Test
    @DisplayName("结构化：exitStatus 非 0 时错误建议带上退出码")
    public void test_structured_exit_code_hint() {
        SshCommandResult r = SshCommandResult.builder()
                .stdout("").stderr("no such file").exitStatus(2).build();
        assertTrue(CommandOutcomeAnalyzer.analyzeError(r).contains("退出码 2"));
    }
}

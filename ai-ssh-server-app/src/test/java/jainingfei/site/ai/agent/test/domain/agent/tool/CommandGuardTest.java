package jainingfei.site.ai.agent.test.domain.agent.tool;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.CommandGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令守卫（CommandGuard）单元测试——危险命令拦截 + 空命令熔断，纯内存无网络。
 *
 * <p>两个熔断语义必须成立：
 * <pre>
 *   连续空命令按 connectionId 独立计数；有效命令（resetEmptyCommands）清零；
 *   达到 MAX_EMPTY_CALLS 后 isInterrupted 报中断，提示模型改用文字回答
 * </pre>
 */
public class CommandGuardTest {

    private final CommandGuard guard = new CommandGuard();

    // ===== 空命令熔断 =====

    @Test
    @DisplayName("同一连接连续空命令计数递增，达到 MAX_EMPTY_CALLS 后报中断")
    public void test_empty_command_counter_reaches_threshold() {
        String connectionId = "conn-1";

        assertEquals(1, guard.onEmptyCommand(connectionId));
        assertFalse(guard.isInterrupted(1));
        assertEquals(2, guard.onEmptyCommand(connectionId));
        assertFalse(guard.isInterrupted(2), "未达阈值不得中断");

        int third = guard.onEmptyCommand(connectionId);
        assertEquals(CommandGuard.MAX_EMPTY_CALLS, third);
        assertTrue(guard.isInterrupted(third), "达到阈值必须中断，避免模型无限空调用");
    }

    @Test
    @DisplayName("resetEmptyCommands 清零计数：有效命令到达后重新从 1 开始")
    public void test_reset_clears_counter() {
        String connectionId = "conn-1";
        guard.onEmptyCommand(connectionId);
        guard.onEmptyCommand(connectionId);

        guard.resetEmptyCommands(connectionId);

        assertEquals(1, guard.onEmptyCommand(connectionId), "清零后重新计数");
        assertFalse(guard.isInterrupted(1));
    }

    @Test
    @DisplayName("不同 connectionId 的空命令计数互不影响（终端会话隔离）")
    public void test_counters_are_per_connection() {
        for (int i = 0; i < CommandGuard.MAX_EMPTY_CALLS; i++) {
            guard.onEmptyCommand("conn-A");
        }
        assertTrue(guard.isInterrupted(CommandGuard.MAX_EMPTY_CALLS));
        assertTrue(guard.isInterrupted(guard.onEmptyCommand("conn-A")));

        assertEquals(1, guard.onEmptyCommand("conn-B"), "另一条连接从 1 开始，不继承 A 的计数");

        guard.resetEmptyCommands("conn-A");
        assertEquals(1, guard.onEmptyCommand("conn-A"));
        assertEquals(2, guard.onEmptyCommand("conn-B"), "A 的清零不得影响 B");
    }

    @Test
    @DisplayName("isInterrupted 是纯阈值判断：负数与 0 都不算中断")
    public void test_is_interrupted_boundary() {
        assertFalse(guard.isInterrupted(0));
        assertFalse(guard.isInterrupted(-1));
        assertFalse(guard.isInterrupted(CommandGuard.MAX_EMPTY_CALLS - 1));
        assertTrue(guard.isInterrupted(CommandGuard.MAX_EMPTY_CALLS));
        assertTrue(guard.isInterrupted(CommandGuard.MAX_EMPTY_CALLS + 5));
        assertEquals(3, CommandGuard.MAX_EMPTY_CALLS, "阈值是配置契约，变更需同步提示文案");
    }

    // ===== 危险命令分类 =====

    @Test
    @DisplayName("危险命令：针对具体路径的 rm -rf / 格式化 / 777 提权被拦截")
    public void test_dangerous_commands_blocked() {
        assertTrue(guard.isDangerous("rm -rf /etc/passwd"));
        assertTrue(guard.isDangerous("sudo rm -rf /var/lib/mysql"));
        assertTrue(guard.isDangerous("mkfs.ext4 /dev/sda1"));
        assertTrue(guard.isDangerous("chmod -R 777 /var/www"));
        assertTrue(guard.isDangerous("RM -RF /HOME/DATA"), "大小写不敏感");
    }

    @Test
    @DisplayName("安全命令与 null 不误报")
    public void test_safe_commands_not_blocked() {
        assertFalse(guard.isDangerous("ls -l"));
        assertFalse(guard.isDangerous("df -h"));
        assertFalse(guard.isDangerous("rm -rf ./build"), "相对路径清理不应误拦");
        assertFalse(guard.isDangerous("rm file.txt"));
        assertFalse(guard.isDangerous(null), "null 必须安全返回 false");
        assertFalse(guard.isDangerous(""));
    }

    @Test
    @DisplayName("灾难命令必须拦截：裸 rm -rf / 与 fork 炸弹（旧正则漏拦，本轮修正）")
    public void test_catastrophic_commands_blocked() {
        // 旧正则写成 \b(...)\b，分支结尾都是非单词字符，导致串尾/后接非单词字符时匹配失败：
        //   "rm -rf /"        → / 之后是串尾，无边界 → 曾漏拦最经典的删库命令
        //   "dd if=/dev/sda"  → = 之后是 /，两侧都非单词字符 → 曾漏拦
        //   ":(){ :|:& };:"   → { 之后是空格 → 曾漏拦 fork 炸弹
        // 现在按"命令起始位置"锚定（串首或 shell 分隔符之后），下列命令全部命中。
        assertTrue(guard.isDangerous("rm -rf /"), "删库命令必须拦截");
        assertTrue(guard.isDangerous("sudo rm -rf /"));
        assertTrue(guard.isDangerous("dd if=/dev/zero of=/dev/sda"));
        assertTrue(guard.isDangerous(":(){ :|:& };:"), "fork 炸弹必须拦截");
        assertTrue(guard.isDangerous("echo x > /dev/sda"));
        assertTrue(guard.isDangerous("chmod -R 777 /"));
    }

    @Test
    @DisplayName("命令起始位置锚定：词内出现的危险片段不误报")
    public void test_dangerous_pattern_anchored_at_command_start() {
        assertFalse(guard.isDangerous("notrm -rf /"), "同一单词内的片段不算命令开头");
        assertFalse(guard.isDangerous("echo rm -rf ./build"), "相对路径清理不误拦");
        assertTrue(guard.isDangerous("cd /tmp;rm -rf /data"), "分隔符之后是新命令开头");
        assertTrue(guard.isDangerous("sh -c \"chmod -R 777 /\""), "引号之后同样算命令开头");
    }

    @Test
    @DisplayName("危险判定是 find 语义：命令片段出现在长命令里也能命中")
    public void test_dangerous_matched_anywhere_in_command() {
        assertTrue(guard.isDangerous("cd /tmp && rm -rf /data && echo done"));
        assertTrue(guard.isDangerous("sh -c 'mkfs.xfs -f /dev/nvme0n1'"));
    }

    @Test
    @DisplayName("熔断后清零可重新计数：第二轮同样能再次达到阈值")
    public void test_counter_reset_cycle() {
        String connectionId = "conn-cycle";
        guard.onEmptyCommand(connectionId);
        guard.onEmptyCommand(connectionId);
        assertEquals(CommandGuard.MAX_EMPTY_CALLS, guard.onEmptyCommand(connectionId));
        assertTrue(guard.isInterrupted(CommandGuard.MAX_EMPTY_CALLS));

        guard.resetEmptyCommands(connectionId);
        assertEquals(1, guard.onEmptyCommand(connectionId));
        guard.onEmptyCommand(connectionId);
        assertEquals(CommandGuard.MAX_EMPTY_CALLS, guard.onEmptyCommand(connectionId),
                "清零后必须能再次走满阈值，熔断逻辑不是一次性的");
    }
}

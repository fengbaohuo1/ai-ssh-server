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

    // ===== 只读智能体的写操作识别（isMutating） =====

    @Test
    @DisplayName("isMutating：文件变更类命令命中")
    public void test_mutating_file_operations() {
        assertTrue(guard.isMutating("rm -rf /tmp/x"));
        assertTrue(guard.isMutating("mv a.txt b.txt"));
        assertTrue(guard.isMutating("cp -r /etc /backup"));
        assertTrue(guard.isMutating("mkdir -p /tmp/a/b"));
        assertTrue(guard.isMutating("touch /tmp/marker"));
        assertTrue(guard.isMutating("chmod 644 /etc/hosts"));
        assertTrue(guard.isMutating("chown root:root /etc/hosts"));
        assertTrue(guard.isMutating("ln -s /a /b"));
        assertTrue(guard.isMutating("tee /etc/hosts"));
        assertTrue(guard.isMutating("sed -i 's/a/b/' /etc/hosts"));
        assertTrue(guard.isMutating("sed -i.bak 's/a/b/' /etc/hosts"));
        assertTrue(guard.isMutating("find /var/log -name '*.log' -delete"));
        assertTrue(guard.isMutating("vim /etc/hosts"));
        assertTrue(guard.isMutating("echo hacked > /etc/hosts"), "输出重定向写文件");
        assertTrue(guard.isMutating("cat a >> b"), "追加重定向写文件");
    }

    @Test
    @DisplayName("isMutating：服务/包/进程/用户/容器等系统变更命中")
    public void test_mutating_system_operations() {
        assertTrue(guard.isMutating("systemctl restart nginx"));
        assertTrue(guard.isMutating("sudo systemctl daemon-reload"));
        assertTrue(guard.isMutating("apt-get install -y nginx"));
        assertTrue(guard.isMutating("pip install requests"));
        assertTrue(guard.isMutating("docker rm -f web"));
        assertTrue(guard.isMutating("kubectl delete pod web-1"));
        assertTrue(guard.isMutating("git push origin master"));
        assertTrue(guard.isMutating("git reset --hard"));
        assertTrue(guard.isMutating("useradd bob"));
        assertTrue(guard.isMutating("kill -9 1234"));
        assertTrue(guard.isMutating("reboot"));
        assertTrue(guard.isMutating("mount /dev/sdb1 /mnt"));
        assertTrue(guard.isMutating("crontab -e"));
        assertTrue(guard.isMutating("curl -o /tmp/x http://example.com"));
        assertTrue(guard.isMutating("wget -O /tmp/y http://example.com"));
        assertTrue(guard.isMutating("curl -X POST http://example.com/api"));
    }

    @Test
    @DisplayName("isMutating：只读诊断命令不得误伤")
    public void test_readonly_diagnostics_not_mutating() {
        assertFalse(guard.isMutating("ls -l /etc"));
        assertFalse(guard.isMutating("cat /var/log/syslog"));
        assertFalse(guard.isMutating("grep -rn 'error' /var/log"));
        assertFalse(guard.isMutating("ps aux | grep java"));
        assertFalse(guard.isMutating("df -h"));
        assertFalse(guard.isMutating("du -sh /var/* 2>/dev/null"));
        assertFalse(guard.isMutating("free -m"));
        assertFalse(guard.isMutating("top -b -n1"));
        assertFalse(guard.isMutating("journalctl -u nginx --no-pager -n 100"));
        assertFalse(guard.isMutating("systemctl status nginx"));
        assertFalse(guard.isMutating("ss -tlnp"));
        assertFalse(guard.isMutating("tail -n 100 /var/log/nginx/access.log"));
        assertFalse(guard.isMutating("sed -n '1,10p' /etc/hosts"), "sed 只读打印不算写");
        assertFalse(guard.isMutating("grep -i error f"), "grep -i 不是 sed -i");
        assertFalse(guard.isMutating("uname -a && uptime"));
        assertFalse(guard.isMutating("git status"));
        assertFalse(guard.isMutating("git log --oneline -n 5"));
        assertFalse(guard.isMutating("docker ps"));
        assertFalse(guard.isMutating("kubectl get pods"));
        assertFalse(guard.isMutating("curl -s http://127.0.0.1:8091/api/v1/system/health"));
        // shell 重定向排除：2>&1 / >&2 / >/dev/null / 数字比较都不算写
        assertFalse(guard.isMutating("ls /proc/*/fd 2>&1 | head"));
        assertFalse(guard.isMutating("ls 2>/dev/null"));
        assertFalse(guard.isMutating("cmd >&2"));
        assertFalse(guard.isMutating("[ 3 -gt 2 ]"), "数字比较不是重定向");
        // 文本箭头不是重定向（真实测试中被误拦：find -printf / docker inspect --format）
        assertFalse(guard.isMutating("find /proc/1/fd -lname '*deleted*' -printf '%p -> %l\\n'"));
        assertFalse(guard.isMutating("docker inspect redis --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'"));
        assertFalse(guard.isMutating("ls -l | awk '{print $1\" => \"$9}'"));
        // 防火墙查询类命令是只读诊断
        assertFalse(guard.isMutating("iptables -L INPUT -n"));
        assertFalse(guard.isMutating("iptables -t nat -L -n --line-numbers"));
        assertFalse(guard.isMutating("iptables -S"));
        assertFalse(guard.isMutating("ip6tables -L INPUT -n"));
        assertFalse(guard.isMutating("nft list ruleset"));
        assertFalse(guard.isMutating("ufw status verbose"));
        assertFalse(guard.isMutating("firewall-cmd --list-all"));
        assertFalse(guard.isMutating("firewall-cmd --state"));
    }

    @Test
    @DisplayName("isMutating：防火墙变更与真实重定向仍然拦截")
    public void test_mutating_firewall_and_redirect_still_blocked() {
        assertTrue(guard.isMutating("iptables -A INPUT -p tcp --dport 80 -j ACCEPT"));
        assertTrue(guard.isMutating("iptables -I INPUT 1 -s 1.2.3.4 -j DROP"));
        assertTrue(guard.isMutating("iptables -F"));
        assertTrue(guard.isMutating("ip6tables -P INPUT DROP"));
        assertTrue(guard.isMutating("nft add rule inet filter input tcp dport 80 accept"));
        assertTrue(guard.isMutating("nft flush ruleset"));
        assertTrue(guard.isMutating("ufw allow 22/tcp"));
        assertTrue(guard.isMutating("ufw disable"));
        assertTrue(guard.isMutating("firewall-cmd --add-port=80/tcp --permanent"));
        assertTrue(guard.isMutating("firewall-cmd --reload"));
        // 重定向本身照旧拦截
        assertTrue(guard.isMutating("echo x > /tmp/a"));
        assertTrue(guard.isMutating("echo x >> /var/log/y"));
        assertTrue(guard.isMutating("awk '$3==1{print}' /proc/diskstats > /tmp/.d1"));
        assertTrue(guard.isMutating("cmd 2>/tmp/err"));
        assertTrue(guard.isMutating("date &> /tmp/x"));
    }

    @Test
    @DisplayName("isMutating：null / 空白安全返回 false（空命令交给熔断处理）")
    public void test_mutating_null_and_blank() {
        assertFalse(guard.isMutating(null));
        assertFalse(guard.isMutating(""));
        assertFalse(guard.isMutating("   "));
    }
}

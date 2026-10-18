package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 命令守卫——命令下发前的两道防线（策略集中，便于单测与后续配置化）。
 *
 * <pre>
 *   ① 危险命令拦截：正则命中直接拒绝执行（防模型幻觉导致的系统损坏）
 *   ② 空命令熔断：同一连接上连续多次空参数调用达到阈值后中断，提示模型改用文字回答
 * </pre>
 *
 * <p>空命令计数按 connectionId 维护（语义与原实现一致：连续计数、有效命令或触发熔断后清零）；
 * 危险命令判定在"终端会话存在"之后执行，保持原有的判定次序与提示文案。
 */
@Slf4j
@Component
public class CommandGuard {

    /** 连续空命令上限（达到即中断调用） */
    public static final int MAX_EMPTY_CALLS = 3;

    /**
     * 危险命令模式——直接拦截不执行。
     *
     * <p><b>边界写法（一轮真 bug 的修正）</b>：原来写的是 {@code \b(...)\b}，两头的 {@code \b}
     * 都要求相邻的是"单词字符侧"，而这些分支的结尾全是非单词字符（{@code /}、{@code =}、{@code \{}、{@code d}），
     * 于是 {@code rm -rf /}、{@code dd if=/dev/sda}、{@code :(){ :|:& };:} 这类命令在串尾
     * 或后接非单词字符时<b>反而匹配不上</b>——最经典的删库命令是漏拦的，而 {@code rm -rf /etc}
     * （结尾后还有单词字符）却能命中，看起来"在工作"。
     *
     * <p>现在改为<b>命令起始位置锚定</b>：只有串首或 shell 分隔符（空白、{@code ; & | ( }、
     * 引号、反引号）之后才算一条命令的开头，词内出现不算（{@code notrm -rf /} 不误报），
     * 结尾不再加任何边界。
     */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?:^|[\\s;&|(\"'`])(?:rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)",
            Pattern.CASE_INSENSITIVE
    );

    /** connectionId → 连续空命令次数 */
    private final ConcurrentHashMap<String, AtomicInteger> emptyCallCounters = new ConcurrentHashMap<>();

    /**
     * 只读智能体（{@code read-only: true}）的写操作识别——命中即拒绝执行。
     *
     * <p>为什么需要这一层：此前 read-only 只是"不挂 writeRemoteFile 工具"，而 executeCommand
     * 是无条件挂载的，于是 {@code rm -rf /etc/x}、{@code mv a b}、{@code sed -i}、
     * {@code systemctl restart} 这些破坏性命令照样放行——"只读"只是一个提示词约定。
     *
     * <p>策略是<b>写操作黑名单</b>（宁可误拦、不可漏放）：只读诊断的合法命令面很宽
     * （ls/cat/grep/ps/df/du/free/top/journalctl status……），而写操作高度集中在少数动词与
     * shell 语法上。误报时模型会收到明确提示：需要变更请派发执行型子智能体或由用户手动操作。
     *
     * <p><b>注意</b>：这是应用层护栏，不是 OS 沙箱——{@code python -c "open(...)"}、
     * {@code find -exec} 内的写操作等长尾无法穷举，真正的强隔离应落在 SSH 账号权限（只读账号）上。
     */
    private static final Pattern[] MUTATING_PATTERNS = {
            // 文件系统变更
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:rm|rmdir|mv|cp|ln|touch|mkdir|truncate|shred|dd|chmod|chown|chgrp|setfacl|chattr|mknod|mkfifo|install|rsync|scp|sftp|tee)(?=\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 原地编辑：sed -i / sed -i.bak / perl -i
            Pattern.compile("\\b(?:sed|perl)\\b[^;&|\\n]*\\s-[a-zA-Z]*i[a-zA-Z]*(?:[.\\s]|$)", Pattern.CASE_INSENSITIVE),
            // 交互式编辑器（会挂起非交互会话）
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:vi|vim|nvim|nano|emacs|pico)(?=\\s|$)", Pattern.CASE_INSENSITIVE),
            // 包管理：安装/卸载/升级
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:apt|apt-get|aptitude|yum|dnf|zypper|pacman|apk|brew|snap|pip|pip3|npm|yarn|pnpm|gem|composer)\\s+(?:install|uninstall|remove|purge|update|upgrade|add|rm|ci|reinstall|dist-upgrade|autoremove|clean)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 服务/进程/系统状态变更
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:systemctl|service|supervisorctl)\\s+(?:start|stop|restart|reload|try-restart|force-reload|enable|disable|mask|unmask|kill|daemon-reload|set-property|set-default)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:kill|killall|pkill|xkill|renice|shutdown|reboot|halt|poweroff|telinit|init)(?=\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 磁盘/挂载/分区/定时任务
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:mount|umount|swapon|swapoff|fdisk|parted|mkfs|mkfs\\.[a-z0-9]+|lvcreate|lvremove|vgcreate|vgremove|mdadm|crontab|at)(?=\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 用户/权限/防火墙
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:useradd|userdel|usermod|groupadd|groupdel|passwd|chpasswd|visudo|iptables|ip6tables|nft|ufw|firewall-cmd)(?=\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 容器/编排
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:docker|podman)\\s+(?:run|create|start|stop|restart|kill|rm|rmi|pull|push|build|tag|commit|exec|cp|pause|unpause|prune|volume|network)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:kubectl|helm)\\s+(?:apply|delete|create|edit|patch|scale|rollout|exec|cp|drain|cordon|uncordon|label|annotate|upgrade|install|uninstall)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 版本库：会改动工作区或远端
            Pattern.compile("(?:^|[\\s;&|(\"'`])git\\s+(?:clone|pull|fetch|checkout|switch|reset|commit|push|merge|rebase|clean|stash|apply|am|cherry-pick|tag|remote\\s+(?:add|remove|set-url))\\b",
                    Pattern.CASE_INSENSITIVE),
            // find 的删除动作
            Pattern.compile("(?:^|\\s)-delete\\b", Pattern.CASE_INSENSITIVE),
            // 输出重定向写文件（排除 2>&1、>&2、>/dev/null 与数字比较）
            Pattern.compile("(?:^|[^<>])>{1,2}\\s*(?!&|/dev/null\\b|[0-9])"),
            // 下载落盘 / 上传 / 发变更请求
            Pattern.compile("(?:^|[\\s;&|(\"'`])(?:curl|wget)\\b[^;&|\\n]*(?:\\s-o\\b|\\s-O\\b|--output|--remote-name|--upload-file|\\s-T\\b|\\s-X\\s*(?:POST|PUT|DELETE|PATCH)|--data|-d\\s|--form|-F\\s)",
                    Pattern.CASE_INSENSITIVE)
    };

    /** 记录一次空命令调用，返回连续次数 */
    public int onEmptyCommand(String connectionId) {
        return emptyCallCounters
                .computeIfAbsent(connectionId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /** 达到熔断阈值 */
    public boolean isInterrupted(int emptyCount) {
        return emptyCount >= MAX_EMPTY_CALLS;
    }

    /** 清零空命令计数（有效命令到达、或熔断中断后调用） */
    public void resetEmptyCommands(String connectionId) {
        emptyCallCounters.remove(connectionId);
    }

    /** 是否命中危险命令模式 */
    public boolean isDangerous(String command) {
        return command != null && DANGEROUS_PATTERN.matcher(command).find();
    }

    /**
     * 是否包含"写操作"（只读智能体专用判定）。
     *
     * @param command 待执行命令，null/空白视为非写操作（空命令由空命令熔断处理）
     * @return true 表示命中写操作黑名单，只读智能体应拒绝执行
     */
    public boolean isMutating(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern pattern : MUTATING_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}

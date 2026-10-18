package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import jainingfei.site.ai.agent.domain.agent.support.AgentTaskContextPropagator;
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
 *   ② 空命令熔断：同一 <b>会话/智能体维度 + connectionId</b> 上连续多次空参数调用达到阈值后中断，
 *      提示模型改用文字回答
 * </pre>
 *
 * <p><b>空命令计数维度</b>：只按 connectionId 计数时，同一条 SSH 连接上并发跑的多个子智能体
 * （规划/批量派发）会互相累加计数——A 的两次空调用 + B 的一次就触发熔断，冤枉了 B。
 * 现在计数 key = {@code 作用域 \0 connectionId}：作用域由 {@link AgentTaskContextPropagator#currentScope()}
 * 提供（{@code userId@appName/agentName}），取不到时退化为原 connectionId 维度，保持单智能体/单测行为不变。
 *
 * <p>危险命令判定在"终端会话存在"之后执行，保持原有的判定次序与提示文案。
 */
@Slf4j
@Component
public class CommandGuard {

    /** 连续空命令上限（达到即中断调用） */
    public static final int MAX_EMPTY_CALLS = 3;

    /** 复合计数 key 的分隔符（作用域与 connectionId 都不含 \u0000，不会歧义） */
    private static final char KEY_SEPARATOR = '\u0000';

    /** 熔断提示：保留可读性，明确要求模型停止调用工具、改用文字回答 */
    public static final String INTERRUPT_MESSAGE =
            "已中断重复调用。请直接用文字向用户说明当前情况，不要再调用 executeCommand。";

    /** 参数缺失提示（空命令但未达熔断阈值） */
    public static final String MISSING_PARAM_MESSAGE =
            "参数缺失：executeCommand 必须提供 command 参数。示例：command=\"ls -la\"";

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

    /** 复合计数 key（{@code 作用域 \0 connectionId}）→ 连续空命令次数 */
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
     * {@code find -exec} 内的写操作等长尾无法穷举，真正的强隔离应落在 SSH 账号权限（只读账号）上。</p>
     *
     * <p><b>误杀修正</b>（真实服务器端到端测试发现）：
     * <ul>
     *   <li>输出重定向正则不能把文本箭头 {@code ->} / {@code =>} 当重定向——{@code find -printf '%p -> %l'}、
     *       {@code docker inspect --format '{{.Source}} -> {{.Destination}}'} 会被误拦；</li>
     *   <li>防火墙类命令要区分查询与变更：{@code iptables -L/-S}、{@code nft list}、{@code ufw status}、
     *       {@code firewall-cmd --list-*} 是只读诊断，只有带变更参数才算写。</li>
     * </ul>
     *
     * <p><b>命令起始锚定</b>（第二次真实端到端测试发现）：原来的前缀 {@code (?:^|[\s;&|("'`])}
     * 把<b>任意空白</b>都当成命令边界，于是作为参数/普通文本出现的动词也被判成写操作——
     * {@code last reboot}、{@code grep "reboot" /var/log/messages}、{@code echo "log at 10:00"}
     * 都会在只读智能体里被误拦。现在统一用 {@link #CMD_START}：只有串首或真正的 shell 分隔符
     * （{@code ; & | (}、换行）之后，允许前缀 {@code sudo/nohup/exec/command/time} 与
     * {@code then/do/else}，才认为是一条命令的开头。
     *
     * <p>收紧后的漏拦用"间接执行"模式兜住：{@code xargs rm}、{@code find ... -exec rm}。
     * 仍然存在长尾（{@code bash -c "rm ..."}、{@code python -c "open(...)"}），所以真正的强隔离
     * 必须落在 SSH 账号权限上——这层只是护栏，不是沙箱。
     */
    /**
     * 命令起始锚点：串首或 shell 分隔符（{@code ; & | ( }、换行）之后，允许 {@code sudo/nohup/exec/command/time}
     * 前缀与 {@code then/do/else} 这类 shell 关键字。
     *
     * <p>刻意<b>不</b>把引号当边界：否则 {@code grep "reboot" /var/log/messages}、
     * {@code journalctl --since 'reboot'} 会被误判成写操作。
     */
    private static final String CMD_START =
            "(?:^|[;&|(\\n])\\s*(?:(?:sudo|nohup|exec|command|time)\\s+)*(?:(?:then|do|else)\\s+)?";

    private static final Pattern[] MUTATING_PATTERNS = {
            // 文件系统变更
            Pattern.compile(CMD_START + "(?:rm|rmdir|mv|cp|ln|touch|mkdir|truncate|shred|dd|chmod|chown|chgrp|setfacl|chattr|mknod|mkfifo|install|rsync|scp|sftp|tee)(?![\\w-])",
                    Pattern.CASE_INSENSITIVE),
            // 间接执行写操作：xargs ... <写动词> / find ... -exec <写动词>（收紧起始锚点后防止漏拦）
            Pattern.compile("(?:xargs\\s+(?:-\\S+\\s+)*|find\\b[^;&|\\n]*-exec\\s+)(?:rm|rmdir|mv|cp|ln|touch|mkdir|truncate|shred|dd|chmod|chown|chgrp|setfacl|chattr|tee|sh|bash|python3?|perl)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 原地编辑：sed -i / sed -i.bak / perl -i
            Pattern.compile("\\b(?:sed|perl)\\b[^;&|\\n]*\\s-[a-zA-Z]*i[a-zA-Z]*(?:[.\\s]|$)", Pattern.CASE_INSENSITIVE),
            // 交互式编辑器（会挂起非交互会话）
            Pattern.compile(CMD_START + "(?:vi|vim|nvim|nano|emacs|pico)(?![\\w-])", Pattern.CASE_INSENSITIVE),
            // 包管理：安装/卸载/升级
            Pattern.compile(CMD_START + "(?:apt|apt-get|aptitude|yum|dnf|zypper|pacman|apk|brew|snap|pip|pip3|npm|yarn|pnpm|gem|composer)\\s+(?:install|uninstall|remove|purge|update|upgrade|add|rm|ci|reinstall|dist-upgrade|autoremove|clean)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 服务/进程/系统状态变更
            Pattern.compile(CMD_START + "(?:systemctl|service|supervisorctl)\\s+(?:start|stop|restart|reload|try-restart|force-reload|enable|disable|mask|unmask|kill|daemon-reload|set-property|set-default)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_START + "(?:kill|killall|pkill|xkill|renice|shutdown|reboot|halt|poweroff|telinit|init)(?![\\w-])",
                    Pattern.CASE_INSENSITIVE),
            // 磁盘/挂载/分区/定时任务
            Pattern.compile(CMD_START + "(?:mount|umount|swapon|swapoff|fdisk|parted|mkfs|mkfs\\.[a-z0-9]+|lvcreate|lvremove|vgcreate|vgremove|mdadm|crontab|at)(?![\\w-])",
                    Pattern.CASE_INSENSITIVE),
            // 用户/权限
            Pattern.compile(CMD_START + "(?:useradd|userdel|usermod|groupadd|groupdel|passwd|chpasswd|visudo)(?![\\w-])",
                    Pattern.CASE_INSENSITIVE),
            // 防火墙：只放行"查询类"用法，变更类参数才算写（一轮实测误杀修正：iptables -L 被整条拦掉）
            // 注意这里必须大小写敏感：iptables 的 -n/-f/-x/-z 是查询/展示选项，-N/-F/-X/-Z 才是变更
            Pattern.compile(CMD_START + "(?:iptables|ip6tables)\\b[^;&|\\n]*(?:-A\\b|--append|-D\\b|--delete|-I\\b|--insert|-R\\b|--replace|-P\\b|--policy|-N\\b|--new-chain|-X\\b|--delete-chain|-F\\b|--flush|-Z\\b|--zero|-E\\b|--rename-chain)"),
            Pattern.compile(CMD_START + "nft\\s+(?:add|insert|delete|flush|create|rename|replace|destroy)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_START + "ufw\\s+(?:enable|disable|default|allow|deny|reject|limit|delete|insert|reset|reload)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_START + "firewall-cmd\\b[^;&|\\n]*(?:--add-|--remove-|--reload|--complete-reload|--set-|--change-|--delete-|--panic-on|--panic-off|--lockdown-on|--lockdown-off)",
                    Pattern.CASE_INSENSITIVE),
            // 容器/编排
            Pattern.compile(CMD_START + "(?:docker|podman)\\s+(?:run|create|start|stop|restart|kill|rm|rmi|pull|push|build|tag|commit|exec|cp|pause|unpause|prune|volume|network)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_START + "(?:kubectl|helm)\\s+(?:apply|delete|create|edit|patch|scale|rollout|exec|cp|drain|cordon|uncordon|label|annotate|upgrade|install|uninstall)\\b",
                    Pattern.CASE_INSENSITIVE),
            // 版本库：会改动工作区或远端
            Pattern.compile(CMD_START + "git\\s+(?:clone|pull|fetch|checkout|switch|reset|commit|push|merge|rebase|clean|stash|apply|am|cherry-pick|tag|remote\\s+(?:add|remove|set-url))\\b",
                    Pattern.CASE_INSENSITIVE),
            // find 的删除动作
            Pattern.compile("(?:^|\\s)-delete\\b", Pattern.CASE_INSENSITIVE),
            // 输出重定向写文件（排除 2>&1、>&2、>/dev/null、数字比较，以及 -> / => 这类文本箭头）
            Pattern.compile("(?<![-<>=])>{1,2}\\s*(?!&|/dev/null\\b|[0-9])"),
            // 下载落盘 / 上传 / 发变更请求
            Pattern.compile(CMD_START + "(?:curl|wget)\\b[^;&|\\n]*(?:\\s-o\\b|\\s-O\\b|--output|--remote-name|--upload-file|\\s-T\\b|\\s-X\\s*(?:POST|PUT|DELETE|PATCH)|--data|-d\\s|--form|-F\\s)",
                    Pattern.CASE_INSENSITIVE)
    };

    /**
     * 记录一次空命令调用，返回连续次数。
     *
     * <p>作用域取当前任务上下文（{@link AgentTaskContextPropagator#currentScope()}）；
     * 拿不到作用域时退化为原 connectionId 维度，兼容同步/单测场景。
     */
    public int onEmptyCommand(String connectionId) {
        return onEmptyCommand(AgentTaskContextPropagator.currentScope(), connectionId);
    }

    /**
     * 记录一次空命令调用，返回该"会话/智能体维度 + connectionId"上的连续次数。
     *
     * @param scope        会话/智能体作用域（null/空白 = 只按 connectionId 计数）
     * @param connectionId SSH 连接 ID（可空）
     */
    public int onEmptyCommand(String scope, String connectionId) {
        return emptyCallCounters
                .computeIfAbsent(counterKey(scope, connectionId), k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /** 达到熔断阈值 */
    public boolean isInterrupted(int emptyCount) {
        return emptyCount >= MAX_EMPTY_CALLS;
    }

    /**
     * 空命令决策——工具层一行接入，返回明确失败（{@code success == false}，不会因熔断而变成成功）。
     *
     * <p>熔断命中时立即清零，下一次空命令重新从 1 开始计数（与旧的"中断后 reset"行为一致）。
     *
     * @param scope        会话/智能体作用域（null/空白 = 只按 connectionId 计数）
     * @param connectionId SSH 连接 ID（可空）
     */
    public EmptyCommandDecision onEmptyCommandDecision(String scope, String connectionId) {
        int count = onEmptyCommand(scope, connectionId);
        boolean interrupted = isInterrupted(count);
        if (interrupted) {
            resetEmptyCommands(scope, connectionId);
            log.warn("空命令熔断触发：scope={} connectionId={} count={}", scope, connectionId, count);
        } else {
            log.debug("空命令计数：scope={} connectionId={} count={}", scope, connectionId, count);
        }
        return new EmptyCommandDecision(interrupted, count,
                interrupted ? INTERRUPT_MESSAGE : MISSING_PARAM_MESSAGE);
    }

    /** 清零空命令计数（有效命令到达、或熔断中断后调用；清掉该连接下所有作用域的计数） */
    public void resetEmptyCommands(String connectionId) {
        emptyCallCounters.keySet().removeIf(key -> matchesConnection(key, connectionId));
    }

    /** 清零指定"会话/智能体维度 + connectionId"的计数 */
    public void resetEmptyCommands(String scope, String connectionId) {
        emptyCallCounters.remove(counterKey(scope, connectionId));
    }

    /** 复合 key：作用域存在时 {@code scope \0 connectionId}，否则仅 connectionId（保持旧行为） */
    private static String counterKey(String scope, String connectionId) {
        String connection = connectionId == null ? "" : connectionId;
        if (scope == null || scope.isBlank()) {
            return connection;
        }
        return scope + KEY_SEPARATOR + connection;
    }

    /** key 是否属于指定连接（兼容"仅 connectionId"与"作用域+connectionId"两种形态） */
    private static boolean matchesConnection(String key, String connectionId) {
        String connection = connectionId == null ? "" : connectionId;
        int separator = key.lastIndexOf(KEY_SEPARATOR);
        return (separator >= 0 ? key.substring(separator + 1) : key).equals(connection);
    }

    /**
     * 空命令处理结果。
     *
     * @param interrupted true = 达到熔断阈值（应中断重复调用）
     * @param count       本次计数
     * @param message     给模型的提示文案
     */
    public record EmptyCommandDecision(boolean interrupted, int count, String message) {

        /** 恒定失败：空命令不是成功，熔断更不是成功（工具层据此填 success=false） */
        public boolean success() {
            return false;
        }
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

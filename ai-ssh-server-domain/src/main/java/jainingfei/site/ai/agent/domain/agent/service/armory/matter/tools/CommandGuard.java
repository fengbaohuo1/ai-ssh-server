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
}

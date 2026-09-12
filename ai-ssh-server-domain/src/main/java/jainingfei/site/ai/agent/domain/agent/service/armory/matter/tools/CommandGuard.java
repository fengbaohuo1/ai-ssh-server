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

    /** 危险命令模式——直接拦截不执行 */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
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

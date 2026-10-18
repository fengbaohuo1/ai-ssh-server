package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;

import java.util.stream.Stream;

/**
 * 命令结果分析——把"一次执行结果"翻译成"成功与否 + 失败原因归类"（纯函数，无状态）。
 *
 * <p>为什么不塞进工具类：这是策略而非通信——判定词表与归类话术的调整频率远高于工具本身，
 * 独立出来后可以直接单测（工具类只能靠真机冒烟验证）。
 *
 * <p>两代判据并存：
 * <ul>
 *   <li>结构化（首选）：{@link #isSuccessful(SshCommandResult)} 以<b>退出码</b>为准，
 *       超时 / 截断直接判失败。只有它能区分"命令成功但无输出"与"命令失败但 stdout 为空"</li>
 *   <li>文本（兼容）：{@link #isSuccessful(String)} 是旧实现的启发式，只在拿不到退出码时兜底</li>
 * </ul>
 */
public final class CommandOutcomeAnalyzer {

    /** 失败信号词（命中即视为执行失败） */
    private static final String[] FAILURE_SIGNALS = {
            "command not found", "permission denied", "operation not permitted", "error:",
            "failed", "fatal:", "unable to", "connection refused", "network is unreachable"
    };

    private CommandOutcomeAnalyzer() {
    }

    /**
     * 判断结构化执行结果是否成功。
     *
     * <p>判据优先级：超时/截断 → 失败；有退出码 → 只有 0 算成功；
     * 无退出码（通道异常等）→ 退化到文本启发式。
     */
    public static boolean isSuccessful(SshCommandResult result) {
        if (result == null) {
            return false;
        }
        if (result.isTimedOut() || result.isTruncated()) {
            return false;
        }
        if (result.hasExitStatus()) {
            return result.getExitStatus() == 0;
        }
        return isSuccessful(result.combinedOutput());
    }

    /**
     * 判断命令输出是否代表执行成功。
     *
     * @param output 命令输出（null / 空串视为成功——无输出不等于失败）
     */
    public static boolean isSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }
        String lower = output.toLowerCase();
        return Stream.of(FAILURE_SIGNALS).noneMatch(lower::contains);
    }

    /**
     * 结构化结果的失败原因归类：先区分超时/截断，再按 stderr/stdout 的信号词归类。
     */
    public static String analyzeError(SshCommandResult result) {
        if (result == null) {
            return "执行失败，请检查命令和输出信息。";
        }
        if (result.isTimedOut()) {
            return "命令执行超时，已被主动终止。建议改用更快/非阻塞的命令（如去掉 tail -f、加 --timeout 参数），或拆分成多步。";
        }
        if (result.isTruncated()) {
            return "命令输出超过上限，已被截断。建议缩小输出范围（如 head、grep、单文件查询）后重试。";
        }
        String combined = result.combinedOutput();
        String bySignal = analyzeError(combined);
        if (bySignal != null && !bySignal.startsWith("执行失败")) {
            return bySignal;
        }
        if (result.hasExitStatus() && result.getExitStatus() != 0) {
            return "命令退出码 " + result.getExitStatus() + "，请结合 stderr 检查命令本身或环境。";
        }
        return bySignal;
    }

    /**
     * 按输出中的信号词归类失败原因，给模型一句可执行的建议。
     *
     * @param output 命令输出
     * @return 归类建议；无法归类时返回通用提示
     */
    public static String analyzeError(String output) {
        if (output == null) {
            return null;
        }
        String lower = output.toLowerCase();
        if (lower.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。";
        }
        if (lower.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        }
        if (lower.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lower.contains("connection refused") || lower.contains("network is unreachable")) {
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        }
        return "执行失败，请检查命令和输出信息。";
    }
}


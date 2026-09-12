package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import java.util.stream.Stream;

/**
 * 命令结果分析——把"一段命令输出"翻译成"成功与否 + 失败原因归类"（纯函数，无状态）。
 *
 * <p>为什么不塞进工具类：这是策略而非通信——判定词表与归类话术的调整频率远高于工具本身，
 * 独立出来后可以直接单测（工具类只能靠真机冒烟验证）。
 *
 * <p>注意：这里只做"文本启发式判断"，真正的失败可能被输出伪装（例如命令自身打印 "failed"）；
 * 因此它只用于给模型提供线索，不作为系统决策依据。
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

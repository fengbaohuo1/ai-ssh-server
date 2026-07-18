package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.Annotations;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * SSH 命令执行 ADK 工具 — 为智能体提供在 SSH 终端执行命令的能力。
 * <p>
 * 使用静态字段持有 connectionId（ADK 使用线程池，ThreadLocal 无法跨线程传递）。
 * 通过 ADK 原生 {@code FunctionTool.create()} 注册到 LlmAgent。
 *
 * <pre>
 * 调用链路:
 *   AgentServiceController → ChatService (set currentConnectionId)
 *     → InMemoryRunner.runAsync() → LlmAgent
 *       → SshExecuteAdkTool.executeCommand (read static field)
 *         → ISshTerminalService.executeCommand(connectionId, command)
 *           → UnifiedSessionManager.executeCommand()
 * </pre>
 */
@Slf4j
@Service
public class SshExecuteAdkTool {

    /** 当前绑定的 SSH 连接 ID（静态字段，ADK 线程池中仍可访问） */
    private static String currentConnectionId;

    @Resource
    private ISshTerminalService sshTerminalService;

    /**
     * 设置当前绑定的 SSH 连接 ID
     */
    public static void setCurrentTerminalSession(String connectionId) {
        currentConnectionId = connectionId;
        log.trace("[SshExecuteAdkTool] 设置连接: thread={}, connectionId={}",
                Thread.currentThread().getName(), connectionId);
    }

    /**
     * 清除绑定
     */
    public static void clearCurrentTerminalSession() {
        currentConnectionId = null;
    }

    /**
     * 在 SSH 终端执行命令并返回输出。
     * <p>
     * 被 {@code FunctionTool.create(bean, "executeCommand")} 识别为 ADK 原生工具。
     *
     * @param command 要执行的 Shell 命令
     * @return 包含 command / output / success / suggestion 的结构化结果
     */
    public Map<String, Object> executeCommand(
            @Annotations.Schema(name = "command", description = "要执行的 Shell 命令，如: ls -la, apt install docker.io, docker --version", optional = true)
            String command) {
        String connectionId = currentConnectionId;

        log.info("[executeCommand] thread={} connectionId={} command={}",
                Thread.currentThread().getName(), connectionId, command);

        if (connectionId == null || connectionId.isEmpty()) {
            log.warn("[executeCommand] 未绑定 SSH 连接");
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("output", "未绑定 SSH 终端会话。请先选择一台已连接的服务器。");
            r.put("command", command);
            return r;
        }

        if (command == null || command.isEmpty()) {
            log.warn("[executeCommand] 未提供命令");
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("output", "未提供要执行的命令。请明确指定需要执行的 Shell 命令。");
            r.put("command", command);
            return r;
        }

        if (!sshTerminalService.sessionExists(connectionId)) {
            log.warn("[executeCommand] 终端会话不存在: {}", connectionId);
            return Map.of(
                    "success", false,
                    "output", "SSH 终端会话不存在或已关闭: " + connectionId,
                    "command", command
            );
        }

        try {
            log.info("SSH 执行命令: connectionId={} command={}", connectionId, command);
            String output = sshTerminalService.executeCommand(connectionId, command);
            log.info("SSH 命令执行完成: outputLength={}", output.length());

            boolean success = isExecutionSuccessful(output);

            Map<String, Object> result = new HashMap<>();
            result.put("command", command);
            result.put("output", output);
            result.put("success", success);

            if (!success) {
                result.put("suggestion", analyzeError(output));
            }

            return result;

        } catch (Exception e) {
            log.error("SSH 命令执行异常: connectionId={} command={}", connectionId, command, e);
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("output", "命令执行异常: " + e.getMessage());
            r.put("command", command);
            return r;
        }
    }

    /**
     * 判断命令执行是否成功
     */
    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }

        String lowerOutput = output.toLowerCase();
        String[] errorIndicators = {
                "command not found", "no such file or directory", "permission denied",
                "operation not permitted", "cannot find", "error:", "failed",
                "fatal:", "unable to", "connection refused", "network is unreachable"
        };

        for (String indicator : errorIndicators) {
            if (lowerOutput.contains(indicator)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分析错误并提供解决建议
     */
    private String analyzeError(String output) {
        if (output == null) return null;

        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。建议检查命令名称或安装对应软件包。";
        }
        if (lowerOutput.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lowerOutput.contains("connection refused") || lowerOutput.contains("network is unreachable")) {
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        }
        return "执行失败，请检查命令和输出信息。";
    }
}

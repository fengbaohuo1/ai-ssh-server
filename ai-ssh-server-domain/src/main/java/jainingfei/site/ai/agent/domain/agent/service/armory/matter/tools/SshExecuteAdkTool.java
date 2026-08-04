package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * SSH 命令执行 ADK 工具——直接继承 {@link BaseTool}。
 *
 * <pre>
 * 调用链路:
 *   AgentServiceController → ChatService（写静态 map，key=sessionId）
 *     → InMemoryRunner.runAsync() → LlmAgent
 *       → SshExecuteAdkTool.runAsync(args, toolContext)
 *         → 读 sessionConnections.get(ctx.invocationContext().session().id())
 *           → ISshTerminalService.execCommand(connectionId, command)
 * </pre>
 */
@Slf4j
@Component
@Scope("prototype")
public class SshExecuteAdkTool extends BaseTool {

    /** 连续空命令上限 */
    private static final int MAX_EMPTY_CALLS = 3;

    /** 危险命令模式——直接拦截不执行，防止 LLM 幻觉导致系统损坏 */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** sessionId → connectionId（静态，多会话安全） */
    private static final ConcurrentHashMap<String, String> sessionConnections = new ConcurrentHashMap<>();

    @Resource
    private ISshTerminalService sshTerminalService;

    /** 连续空命令调用计数器（per-connectionId，实例级，线程安全） */
    private final ConcurrentHashMap<String, AtomicInteger> emptyCallCounters = new ConcurrentHashMap<>();

    /**
     * 绑定会话到 SSH 连接。ChatService 在 runAsync 之前调用。
     */
    public static void bindSession(String sessionId, String connectionId) {
        sessionConnections.put(sessionId, connectionId);
        log.debug("绑定会话: sessionId={} connectionId={}", sessionId, connectionId);
    }

    /**
     * 解绑会话。
     */
    public static void unbindSession(String sessionId) {
        sessionConnections.remove(sessionId);
    }

    public SshExecuteAdkTool() {
        super("executeCommand",
                "Execute a shell command on the remote server via SSH. "
                        + "Returns a structured result with command, output, success flag, and optional suggestion.");
    }

    /** 工具声明：参数 schema 在注册时直接注入 LlmAgent */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name("executeCommand")
                .description("Execute a shell command on the remote server via SSH")
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(Map.of("command",
                                Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("Shell command to execute, e.g., ls -la, apt install docker.io, docker --version")
                                        .build()))
                        .required(List.of("command"))
                        .build())
                .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> execute(args, toolContext));
    }

    private Map<String, Object> execute(Map<String, Object> args, ToolContext ctx) {
        // 通过 ADK 会话 ID 从静态 map 中读取 connectionId（ChatService 在调用前写入）
        String sessionId = ctx.invocationContext().session().id();
        String connectionId = sessionConnections.get(sessionId);

        String command = (String) args.get("command");
        log.debug("[EXEC] connectionId={} command='{}'", connectionId, command);

        // 未绑定连接
        if (connectionId == null || connectionId.isEmpty()) {
            log.warn("[EXEC] 未绑定 SSH 连接 → 返回提示");
            return result(true, command, "未绑定 SSH 终端会话。请先选择一台已连接的服务器。", null);
        }

        // 空命令熔断
        if (command == null || command.isEmpty()) {
            int count = emptyCallCounters
                    .computeIfAbsent(connectionId, k -> new AtomicInteger(0))
                    .incrementAndGet();
            log.warn("[EXEC] 空命令 (连续第{}次)", count);

            if (count >= MAX_EMPTY_CALLS) {
                emptyCallCounters.remove(connectionId);
                return result(true, "",
                        "已中断重复调用。请直接用文字向用户说明当前情况，不要再调用 executeCommand。", null);
            }
            return result(false, "", "参数缺失：executeCommand 必须提供 command 参数。示例：command=\"ls -la\"", null);
        }

        // 收到有效命令，重置空调用计数器
        emptyCallCounters.remove(connectionId);

        return doExecute(sessionId, connectionId, command);
    }

    /**
     * 公共执行入口——显式 ReAct 编排模式下由 ToolCallNode 直接调用（无 ADK ToolContext）。
     *
     * <p>与 {@link #runAsync} 路径共用 {@link #doExecute}，连接检查、危险命令拦截、
     * 执行逻辑完全一致。sessionId 由调用方（Case 层）显式传入。
     *
     * @param sessionId ADK 会话 ID（工具绑定 map 的 key）
     * @param command   要执行的 Shell 命令
     * @return 执行结果
     */
    public Map<String, Object> executeCommand(String sessionId, String command) {
        log.debug("[EXEC:public] sessionId={} command='{}'", sessionId, command);

        String connectionId = sessionConnections.get(sessionId);
        if (connectionId == null || connectionId.isEmpty()) {
            log.warn("[EXEC:public] 未绑定 SSH 连接 → 返回提示");
            return result(true, command, "未绑定 SSH 终端会话。请先选择一台已连接的服务器。", null);
        }
        return doExecute(sessionId, connectionId, command);
    }

    /** 共用执行体：连接检查 → 危险命令拦截 → 执行 → 结果组装 */
    private Map<String, Object> doExecute(String sessionId, String connectionId, String command) {
        try {
            if (!sshTerminalService.sessionExists(connectionId)) {
                log.warn("[EXEC] 终端会话不存在: {}", connectionId);
                return result(false, command, "SSH 终端会话不存在或已关闭: " + connectionId, null);
            }

            // 危险命令拦截
            if (DANGEROUS_PATTERN.matcher(command).find()) {
                log.warn("[EXEC] 危险命令被拦截: connectionId={} command={}", connectionId, command);
                return result(false, command,
                        "⚠️ 危险命令被拦截: " + command
                                + "\n该命令可能导致系统损坏或数据丢失。如确需执行，请手动在终端操作。",
                        null);
            }

            log.info("[EXEC] 开始SSH执行: connectionId={} command={}", connectionId, command);
            String output = sshTerminalService.execCommand(connectionId, command);
            log.info("[EXEC] SSH执行完成: outputLength={}", output.length());

            boolean success = isExecutionSuccessful(output);
            return result(success, command, output, success ? null : analyzeError(output));

        } catch (Exception e) {
            log.error("[EXEC] 异常: connectionId={} command={}", connectionId, command, e);
            return result(false, command != null ? command : "",
                    "命令执行异常: " + e.getMessage(), null);
        }
    }

    // ===== 统一返回结构 =====

    private static Map<String, Object> result(boolean success, String command,
                                               String output, String suggestion) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", success);
        r.put("command", command);
        r.put("output", output);
        if (suggestion != null) {
            r.put("suggestion", suggestion);
        }
        return r;
    }

    // ===== 错误检测 =====

    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) return true;
        String lower = output.toLowerCase();
        return !java.util.stream.Stream.of(
                "command not found", "permission denied",
                "operation not permitted", "error:", "failed",
                "fatal:", "unable to", "connection refused", "network is unreachable"
        ).anyMatch(lower::contains);
    }

    private String analyzeError(String output) {
        if (output == null) return null;
        String lower = output.toLowerCase();
        if (lower.contains("command not found"))
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。";
        if (lower.contains("permission denied"))
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        if (lower.contains("no such file or directory"))
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        if (lower.contains("connection refused") || lower.contains("network is unreachable"))
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        return "执行失败，请检查命令和输出信息。";
    }
}

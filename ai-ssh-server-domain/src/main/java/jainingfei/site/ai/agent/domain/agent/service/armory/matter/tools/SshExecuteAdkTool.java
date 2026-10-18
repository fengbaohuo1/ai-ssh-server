package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import jainingfei.site.ai.agent.domain.security.support.AuditContext;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSH 命令执行 ADK 工具——直接继承 {@link BaseTool}。
 *
 * <pre>
 * 调用链路:
 *   AgentServiceController → ChatService（写绑定表，key=ADK 会话 ID）
 *     → InMemoryRunner.runAsync() → LlmAgent
 *       → SshExecuteAdkTool.runAsync(args, toolContext)
 *         → SshSessionBindings.resolveTerminalSession(ctx)（state 优先 → 绑定表兜底）
 *           → ISshTerminalService.execCommand(connectionId, command)
 * </pre>
 *
 * <p>职责边界：本类只管"工具声明 + 参数校验 + 执行编排"，三块策略各自成类：
 * <pre>
 *   SshSessionBindings      终端会话绑定与解析（跨层协议，见 support 包）
 *   CommandGuard            危险命令拦截 + 空命令熔断
 *   CommandOutcomeAnalyzer  成功判定与错误归类（纯函数）
 * </pre>
 */
@Slf4j
@Component
@Scope("prototype")
public class SshExecuteAdkTool extends BaseTool {

    /** 工具输出对模型可见/落库上限（head/tail 保留；托管循环下该文本会以 FunctionResponse 进入下一轮请求） */
    private static final int MAX_TOOL_OUTPUT_LENGTH = 4000;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private CommandGuard commandGuard;

    @Resource
    private IAuditService auditService;

    /**
     * 只读策略——由装配层按智能体的 {@code read-only} 配置注入（每个智能体一个 prototype 实例）。
     *
     * <p>只读智能体的 executeCommand 会在命令级拦截写操作（{@link CommandGuard#isMutating}），
     * 而不是只靠"不挂 writeRemoteFile"和提示词自觉。
     */
    private volatile boolean readOnly;

    public SshExecuteAdkTool() {
        super("executeCommand",
                "Execute a shell command on the remote server via SSH. "
                        + "Returns a structured result with command, output, success flag, exitStatus, and optional suggestion.");
    }

    /** 装配期绑定只读策略（由 {@code AgentNode} 调用） */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
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

    /** 前置校验（连接解析 / 空命令熔断）→ 执行 */
    private Map<String, Object> execute(Map<String, Object> args, ToolContext ctx) {
        String sessionId = ctx.invocationContext().session().id();
        String connectionId = SshSessionBindings.resolveTerminalSession(ctx);
        String command = (String) args.get("command");
        log.debug("[EXEC] connectionId={} command='{}'", connectionId, command);

        if (connectionId == null || connectionId.isEmpty()) {
            log.warn("[EXEC] 未绑定 SSH 连接 → 返回提示");
            return result(false, command, "未绑定 SSH 终端会话。请先选择一台已连接的服务器。", null);
        }

        if (command == null || command.isEmpty()) {
            int count = commandGuard.onEmptyCommand(connectionId);
            log.warn("[EXEC] 空命令 (连续第{}次)", count);

            if (commandGuard.isInterrupted(count)) {
                commandGuard.resetEmptyCommands(connectionId);
                return result(false, "",
                        "已中断重复调用。请直接用文字向用户说明当前情况，不要再调用 executeCommand。", null);
            }
            return result(false, "", "参数缺失：executeCommand 必须提供 command 参数。示例：command=\"ls -la\"", null);
        }

        commandGuard.resetEmptyCommands(connectionId);
        return doExecute(sessionId, connectionId, command);
    }

    /**
     * 公共执行入口——显式 ReAct 编排模式下由 ToolCallNode 直接调用（无 ADK ToolContext）。
     *
     * <p>与 {@link #runAsync} 路径共用 {@link #doExecute}，连接检查、危险命令拦截、
     * 执行逻辑完全一致。sessionId 由调用方（Case 层）显式传入。
     *
     * @param sessionId ADK 会话 ID（绑定表的 key）
     * @param command   要执行的 Shell 命令
     * @return 执行结果
     */
    public Map<String, Object> executeCommand(String sessionId, String command) {
        log.debug("[EXEC:public] sessionId={} command='{}'", sessionId, command);

        String connectionId = SshSessionBindings.connectionOf(sessionId);
        if (connectionId == null || connectionId.isEmpty()) {
            log.warn("[EXEC:public] 未绑定 SSH 连接 → 返回提示");
            return result(false, command, "未绑定 SSH 终端会话。请先选择一台已连接的服务器。", null);
        }
        return doExecute(sessionId, connectionId, command);
    }

    /** 共用执行体：连接存活检查 → 危险/只读拦截 → 执行 → 结果组装 */
    private Map<String, Object> doExecute(String sessionId, String connectionId, String command) {
        long start = System.currentTimeMillis();
        try {
            if (!sshTerminalService.sessionExists(connectionId)) {
                log.warn("[EXEC] 终端会话不存在: {}", connectionId);
                return result(false, command, "SSH 终端会话不存在或已关闭: " + connectionId, null);
            }

            if (commandGuard.isDangerous(command)) {
                log.warn("[EXEC] 危险命令被拦截: connectionId={} command={}", connectionId, command);
                auditService.record(AuditContext.userId(), connectionId, null,
                        AuditActionEnum.COMMAND_EXECUTE, command, "危险命令被拦截",
                        null, false, System.currentTimeMillis() - start, AuditContext.clientIp());
                return result(false, command,
                        "⚠️ 危险命令被拦截: " + command
                                + "\n该命令可能导致系统损坏或数据丢失。如确需执行，请手动在终端操作。",
                        null);
            }

            // 只读智能体：命令级拦截写操作（read-only 不再只是"不挂写文件工具"）
            if (readOnly && commandGuard.isMutating(command)) {
                log.warn("[EXEC] 只读智能体拦截写操作: connectionId={} command={}", connectionId, command);
                auditService.record(AuditContext.userId(), connectionId, null,
                        AuditActionEnum.COMMAND_EXECUTE, command, "只读智能体拦截写操作",
                        null, false, System.currentTimeMillis() - start, AuditContext.clientIp());
                return result(false, command,
                        "⛔ 只读模式已拦截写操作: " + command
                                + "\n当前智能体配置为 read-only，只能执行查看/诊断类命令。"
                                + "\n需要变更时：把任务派发给执行型子智能体，或请用户手动在终端操作。",
                        "只读智能体不允许写操作");
            }

            log.info("[EXEC] 开始SSH执行: connectionId={} command={}", connectionId, command);
            SshCommandResult execResult = sshTerminalService.execCommandDetailed(connectionId, command);
            String combined = execResult.combinedOutput();
            boolean success = CommandOutcomeAnalyzer.isSuccessful(execResult);
            log.info("[EXEC] SSH执行完成: exitStatus={} timedOut={} truncated={} outputLength={}",
                    execResult.getExitStatus(), execResult.isTimedOut(), execResult.isTruncated(), combined.length());

            auditService.record(AuditContext.userId(), connectionId, null,
                    AuditActionEnum.COMMAND_EXECUTE, command,
                    "exit=" + execResult.getExitStatus() + ", stdout=" + execResult.getStdout().length()
                            + ", stderr=" + execResult.getStderr().length(),
                    null, success, System.currentTimeMillis() - start, AuditContext.clientIp());
            // head/tail 保留 + 精确省略计数：命令输出超限时模型只看到首尾（页脚提示可要求补充）
            String boundedOutput = TextRetentionUtil.retainHeadTail(combined, MAX_TOOL_OUTPUT_LENGTH);
            Map<String, Object> r = result(success, command, boundedOutput,
                    success ? null : CommandOutcomeAnalyzer.analyzeError(execResult));
            // 结构化字段：模型据此区分"退出码非 0 / 超时 / 输出被截断"
            if (execResult.hasExitStatus()) {
                r.put("exitStatus", execResult.getExitStatus());
            }
            if (execResult.isTimedOut()) {
                r.put("timedOut", true);
            }
            if (execResult.isTruncated()) {
                r.put("truncated", true);
            }
            return r;

        } catch (Exception e) {
            log.error("[EXEC] 异常: connectionId={} command={}", connectionId, command, e);
            auditService.record(AuditContext.userId(), connectionId, null,
                    AuditActionEnum.COMMAND_EXECUTE, command, e.getMessage(), null, false,
                    System.currentTimeMillis() - start, AuditContext.clientIp());
            return result(false, command != null ? command : "",
                    "命令执行异常: " + e.getMessage(), null);
        }
    }

    /** 统一返回结构（字段名是模型与回合治理共同依赖的契约） */
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
}

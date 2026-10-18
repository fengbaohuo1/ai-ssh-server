package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import jainingfei.site.ai.agent.domain.filesystem.service.IFileSystemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 远程文本写入工具——仅挂载给"非 read-only"的执行型智能体。
 *
 * <p>安全边界：路径经 {@code RemotePathResolver} 拦截 /proc、/sys、/dev 与目录穿越；
 * 写入动作进入审计（FILE_WRITE，HIGH）。真正的权限边界仍由远端账号决定。
 */
@Slf4j
@Component
@Scope("prototype")
public class WriteRemoteFileTool extends BaseTool {

    @Resource
    private IFileSystemService fileSystemService;

    public WriteRemoteFileTool() {
        super("writeRemoteFile",
                "Overwrite a text file on the remote server via SFTP. "
                        + "Use only when the task explicitly requires writing a config file.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name("writeRemoteFile")
                .description("Overwrite a remote text file via SFTP. Dangerous: confirm the path before writing.")
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(Map.of(
                                "path", Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("Remote file path to overwrite")
                                        .build(),
                                "content", Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("Full new file content (UTF-8)")
                                        .build()))
                        .required(List.of("path", "content"))
                        .build())
                .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String connectionId = SshSessionBindings.resolveTerminalSession(toolContext);
            if (connectionId == null || connectionId.isEmpty()) {
                return error("未绑定 SSH 终端会话，请先选择一台已连接的服务器。");
            }
            String path = args.get("path") instanceof String p ? p : null;
            String content = args.get("content") instanceof String c ? c : null;
            if (path == null || path.isBlank()) {
                return error("参数缺失：writeRemoteFile 必须提供 path。");
            }
            if (content == null) {
                return error("参数缺失：writeRemoteFile 必须提供 content。");
            }
            try {
                fileSystemService.writeText(connectionId, path, content);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("path", path);
                result.put("bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                return result;
            } catch (Exception e) {
                log.warn("[SFTP] writeRemoteFile 失败 connectionId={} path={} error={}",
                        connectionId, path, e.getMessage());
                return error(e.getMessage());
            }
        });
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}

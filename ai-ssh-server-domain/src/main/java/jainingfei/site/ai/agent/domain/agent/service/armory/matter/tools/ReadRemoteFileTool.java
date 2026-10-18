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
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpPreviewVO;
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
 * 远程文本读取工具——预览配置文件 / 日志片段，无需 cat 整文件。
 */
@Slf4j
@Component
@Scope("prototype")
public class ReadRemoteFileTool extends BaseTool {

    /** 返回给模型的文本上限（head/tail 保留） */
    private static final int MAX_CONTENT_LENGTH = 4000;

    @Resource
    private IFileSystemService fileSystemService;

    public ReadRemoteFileTool() {
        super("readRemoteFile",
                "Read a text file (or the head of a large file) on the remote server via SFTP. "
                        + "Returns text content, truncation flag and binary flag.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name("readRemoteFile")
                .description("Read a remote text file via SFTP. Binary files return binary=true without content.")
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(Map.of("path",
                                Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("Remote file path, e.g. /etc/nginx/nginx.conf")
                                        .build()))
                        .required(List.of("path"))
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
            if (path == null || path.isBlank()) {
                return error("参数缺失：readRemoteFile 必须提供 path。");
            }
            try {
                SftpPreviewVO preview = fileSystemService.preview(connectionId, path);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("path", preview.getPath());
                result.put("size", preview.getSize());
                result.put("binary", preview.isBinary());
                result.put("truncated", preview.isTruncated());
                result.put("encoding", preview.getEncoding());
                result.put("content", preview.isBinary() ? ""
                        : TextRetentionUtil.retainHeadTail(preview.getContent(), MAX_CONTENT_LENGTH));
                return result;
            } catch (Exception e) {
                // 异常信息可能为空（如 AppException 只带 info、SFTP 包装异常），不能把 null 交给模型
                String detail = e.getMessage();
                if (detail == null || detail.isBlank()) {
                    detail = e.getClass().getSimpleName();
                }
                log.warn("[SFTP] readRemoteFile 失败 connectionId={} path={} error={}",
                        connectionId, path, detail);
                return error("读取失败：" + detail + "（path=" + path + "）");
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

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpDirectoryVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;
import jainingfei.site.ai.agent.domain.filesystem.service.IFileSystemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 远程目录浏览工具——让智能体"看得见文件"，而不必用 shell 解析 ls 输出。
 */
@Slf4j
@Component
@Scope("prototype")
public class ListRemoteFilesTool extends BaseTool {

    private static final int MAX_RETURNED_ENTRIES = 200;

    @Resource
    private IFileSystemService fileSystemService;

    public ListRemoteFilesTool() {
        super("listRemoteFiles",
                "List files and directories on the remote server via SFTP. "
                        + "Returns structured entries (name, type, size, path, modifiedAt).");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name("listRemoteFiles")
                .description("List files/directories on the remote server. Prefer this over parsing 'ls' output.")
                .parameters(Schema.builder()
                        .type(new Type(Type.Known.OBJECT))
                        .properties(Map.of("path",
                                Schema.builder()
                                        .type(new Type(Type.Known.STRING))
                                        .description("Remote directory path; defaults to current directory '.'")
                                        .build()))
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
            String path = args.get("path") instanceof String p && !p.isBlank() ? p : ".";
            try {
                SftpDirectoryVO directory = fileSystemService.list(connectionId, path);
                List<Map<String, Object>> files = new ArrayList<>();
                List<SftpFileVO> source = directory.getFiles() == null ? List.of() : directory.getFiles();
                for (int i = 0; i < source.size() && i < MAX_RETURNED_ENTRIES; i++) {
                    SftpFileVO file = source.get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", file.getName());
                    item.put("path", file.getPath());
                    item.put("type", file.getType() == null ? "other" : file.getType().getCode());
                    item.put("size", file.getSize());
                    item.put("modifiedAt", file.getModifiedAt());
                    files.add(item);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("path", directory.getPath());
                result.put("parent", directory.getParent());
                result.put("total", directory.getTotal());
                result.put("returned", files.size());
                result.put("files", files);
                return result;
            } catch (Exception e) {
                log.warn("[SFTP] listRemoteFiles 失败 connectionId={} path={} error={}",
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

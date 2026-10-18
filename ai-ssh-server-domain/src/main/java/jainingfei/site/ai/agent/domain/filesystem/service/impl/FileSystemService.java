package jainingfei.site.ai.agent.domain.filesystem.service.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.filesystem.adapter.port.ISftpPort;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpDirectoryVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpPreviewVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.enums.SftpFileTypeEnum;
import jainingfei.site.ai.agent.domain.filesystem.service.IFileSystemService;
import jainingfei.site.ai.agent.domain.filesystem.service.support.RemotePathResolver;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import jainingfei.site.ai.agent.domain.security.support.AuditContext;
import jainingfei.site.ai.agent.domain.ssh.adapter.repository.ISshConnectionRepository;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文件系统领域服务实现——每个用例统一走"路径校验 → 限额 → 端口操作 → 审计"。
 */
@Slf4j
@Service
public class FileSystemService implements IFileSystemService {

    @Resource
    private ISftpPort sftpPort;

    @Resource
    private RemotePathResolver pathResolver;

    @Resource
    private SecurityProperties securityProperties;

    @Resource
    private IAuditService auditService;

    @Resource
    private ISshConnectionRepository sshConnectionRepository;

    /** 连接名缓存：审计展示用，避免每次文件操作都查库 */
    private final java.util.Map<String, String> connectionNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public SftpDirectoryVO list(String connectionId, String path) {
        String resolved = pathResolver.resolve(path);
        long start = System.currentTimeMillis();
        try {
            List<SftpFileVO> files = sftpPort.list(connectionId, resolved);
            int max = securityProperties.getSftp().getMaxListEntries();
            List<SftpFileVO> sorted = files.stream()
                    .sorted(Comparator
                            .comparing((SftpFileVO f) -> f.getType() != SftpFileTypeEnum.DIRECTORY)
                            .thenComparing(f -> f.getName() == null ? "" : f.getName().toLowerCase()))
                    .collect(Collectors.toList());
            boolean truncated = sorted.size() > max;
            if (truncated) {
                sorted = sorted.subList(0, max);
            }
            audit(connectionId, AuditActionEnum.FILE_LIST, resolved,
                    "共 " + files.size() + " 项", true, start);
            return SftpDirectoryVO.builder()
                    .path(resolved)
                    .parent(pathResolver.parentOf(resolved))
                    .files(sorted)
                    .total(files.size())
                    .truncated(truncated)
                    .build();
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_LIST, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public SftpFileVO stat(String connectionId, String path) {
        String resolved = pathResolver.resolve(path);
        return sftpPort.stat(connectionId, resolved);
    }

    @Override
    public void mkdir(String connectionId, String path) {
        String resolved = pathResolver.resolve(path);
        long start = System.currentTimeMillis();
        try {
            sftpPort.mkdir(connectionId, resolved);
            audit(connectionId, AuditActionEnum.FILE_MKDIR, resolved, null, true, start);
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_MKDIR, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public void rename(String connectionId, String path, String newPath) {
        String from = pathResolver.resolve(path);
        String to = pathResolver.resolve(newPath);
        if (pathResolver.isRoot(from) || pathResolver.isRoot(to)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不允许重命名根目录");
        }
        if (from.equals(to)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "源路径与目标路径相同");
        }
        // 移动防护：目录不能移动到自身或自己的子目录（否则远端会产生循环/覆盖）
        if (pathResolver.isSameOrDescendant(from, to)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "不能把目录移动到自身或子目录：" + to);
        }
        // 目标存在直接拒绝，避免部分 SFTP 服务端在 rename 时静默覆盖
        if (sftpPort.exists(connectionId, to)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "目标路径已存在：" + to);
        }
        boolean move = !Objects.equals(pathResolver.parentOf(from), pathResolver.parentOf(to));
        AuditActionEnum action = move ? AuditActionEnum.FILE_MOVE : AuditActionEnum.FILE_RENAME;
        long start = System.currentTimeMillis();
        try {
            sftpPort.rename(connectionId, from, to);
            audit(connectionId, action, from, (move ? "移动到 " : "→ ") + to, true, start);
        } catch (RuntimeException e) {
            audit(connectionId, action, from, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public void delete(String connectionId, String path, boolean recursive) {
        String resolved = pathResolver.resolve(path);
        if (pathResolver.isRoot(resolved)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不允许删除根目录");
        }
        long start = System.currentTimeMillis();
        try {
            sftpPort.delete(connectionId, resolved, recursive);
            audit(connectionId, AuditActionEnum.FILE_DELETE, resolved,
                    recursive ? "递归删除" : "删除", true, start);
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_DELETE, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public void upload(String connectionId, String directory, String fileName,
                       InputStream inputStream, long size) {
        long max = securityProperties.getSftp().getMaxUploadBytes();
        if (size > max) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "文件大小 " + size + " 字节超过上传上限 " + max + " 字节");
        }
        String resolved = pathResolver.resolveChild(directory, fileName);
        long start = System.currentTimeMillis();
        try {
            sftpPort.upload(connectionId, resolved, inputStream, size);
            audit(connectionId, AuditActionEnum.FILE_UPLOAD, resolved,
                    "大小 " + size + " 字节", true, start);
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_UPLOAD, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public void download(String connectionId, String path, OutputStream outputStream) {
        String resolved = pathResolver.resolve(path);
        SftpFileVO file = sftpPort.stat(connectionId, resolved);
        if (file.getType() == SftpFileTypeEnum.DIRECTORY) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "目录不支持直接下载");
        }
        long start = System.currentTimeMillis();
        try {
            sftpPort.download(connectionId, resolved, outputStream);
            audit(connectionId, AuditActionEnum.FILE_DOWNLOAD, resolved,
                    "大小 " + file.getSize() + " 字节", true, start);
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_DOWNLOAD, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public SftpPreviewVO preview(String connectionId, String path) {
        return preview(connectionId, path, -1);
    }

    @Override
    public SftpPreviewVO preview(String connectionId, String path, int maxBytes) {
        String resolved = pathResolver.resolve(path);
        SftpFileVO file = sftpPort.stat(connectionId, resolved);
        if (file.getType() == SftpFileTypeEnum.DIRECTORY) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "目录不支持预览");
        }
        int configuredMax = securityProperties.getSftp().getMaxPreviewBytes();
        int effectiveMax = maxBytes > 0 ? Math.min(maxBytes, configuredMax) : configuredMax;
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = sftpPort.readPrefix(connectionId, resolved, effectiveMax);
            boolean binary = isBinary(bytes);
            boolean truncated = file.getSize() > bytes.length;
            String content = binary ? "" : new String(bytes, StandardCharsets.UTF_8);
            audit(connectionId, AuditActionEnum.FILE_PREVIEW, resolved,
                    "预览 " + bytes.length + " 字节", true, start);
            return SftpPreviewVO.builder()
                    .path(resolved)
                    .content(content)
                    .size(file.getSize())
                    .truncated(truncated)
                    .binary(binary)
                    .encoding("UTF-8")
                    .build();
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_PREVIEW, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    @Override
    public void writeText(String connectionId, String path, String content) {
        String resolved = pathResolver.resolve(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        long max = securityProperties.getSftp().getMaxUploadBytes();
        if (bytes.length > max) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "写入内容超过上限 " + max + " 字节");
        }
        long start = System.currentTimeMillis();
        try {
            sftpPort.write(connectionId, resolved, bytes);
            audit(connectionId, AuditActionEnum.FILE_WRITE, resolved,
                    "写入 " + bytes.length + " 字节", true, start);
        } catch (RuntimeException e) {
            audit(connectionId, AuditActionEnum.FILE_WRITE, resolved, e.getMessage(), false, start);
            throw e;
        }
    }

    // ==================== 内部 ====================

    private void audit(String connectionId, AuditActionEnum action, String resource,
                       String detail, boolean success, long start) {
        auditService.record(AuditContext.userId(), connectionId, connectionNameOf(connectionId),
                action, resource, detail, null, success,
                System.currentTimeMillis() - start, AuditContext.clientIp());
    }

    /** 连接名快照（连接删除后审计仍可读）；查询失败返回 null，不影响主流程 */
    private String connectionNameOf(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        return connectionNameCache.computeIfAbsent(connectionId, id -> {
            try {
                SshConnectionAggregate aggregate = sshConnectionRepository.queryByConnectionId(id);
                return aggregate == null || aggregate.getConnection() == null
                        ? null : aggregate.getConnection().getConnectionName();
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** 前 8KB 含 NUL 即视为二进制（文本文件不会出现 NUL） */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}

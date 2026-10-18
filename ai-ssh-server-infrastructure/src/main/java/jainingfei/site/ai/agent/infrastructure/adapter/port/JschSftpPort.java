package jainingfei.site.ai.agent.infrastructure.adapter.port;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.filesystem.adapter.port.ISftpPort;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.enums.SftpFileTypeEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSch SFTP 适配器——每次操作开一个独立 {@link ChannelSftp}，用完即关。
 *
 * <p>为什么复用 JSch {@link Session} 而不是自己建连接：SFTP 与终端共享同一份
 * SSH 连接（认证、保活、超时配置一致），打开新通道是毫秒级操作，无需另建 TCP。
 *
 * <p>并发控制：同一连接上的 SFTP 通道用 per-connection 锁串行化。终端 ChannelShell
 * 走的是另一条通道，不受影响。
 */
@Slf4j
@Component
public class JschSftpPort implements ISftpPort {

    @Resource
    private ISshSessionPort sshSessionPort;

    @Resource
    private SecurityProperties securityProperties;

    /** connectionId → 通道锁 */
    private final ConcurrentHashMap<String, Object> channelLocks = new ConcurrentHashMap<>();

    @Override
    public List<SftpFileVO> list(String connectionId, String path) {
        return withChannel(connectionId, "列目录", channel -> {
            Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
            List<SftpFileVO> result = new ArrayList<>();
            if (entries == null) {
                return result;
            }
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                result.add(toFileVO(connectionId, channel, path, entry));
            }
            return result;
        });
    }

    @Override
    public SftpFileVO stat(String connectionId, String path) {
        return withChannel(connectionId, "查看属性", channel -> {
            SftpATTRS attrs = channel.stat(path);
            String name = path;
            int idx = path.lastIndexOf('/');
            if (idx >= 0) {
                name = path.substring(idx + 1);
            }
            return SftpFileVO.builder()
                    .name(name)
                    .path(path)
                    .type(typeOf(attrs))
                    .size(attrs.getSize())
                    .modifiedAt(attrs.getMTime() * 1000L)
                    .permissions(attrs.getPermissions())
                    .permissionsText(attrs.getPermissionsString())
                    .hidden(name.startsWith("."))
                    .linkTarget(readLinkQuietly(channel, path, attrs))
                    .owner(null)
                    .build();
        });
    }

    @Override
    public boolean exists(String connectionId, String path) {
        return withChannel(connectionId, "查看属性", channel -> {
            try {
                channel.stat(path);
                return true;
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public void mkdir(String connectionId, String path) {
        withChannel(connectionId, "创建目录", channel -> {
            channel.mkdir(path);
            return null;
        });
    }

    @Override
    public void rename(String connectionId, String oldPath, String newPath) {
        withChannel(connectionId, "重命名", channel -> {
            channel.rename(oldPath, newPath);
            return null;
        });
    }

    @Override
    public void delete(String connectionId, String path, boolean recursive) {
        withChannel(connectionId, "删除", channel -> {
            SftpATTRS attrs = channel.stat(path);
            if (!attrs.isDir()) {
                channel.rm(path);
                return null;
            }
            if (!recursive) {
                // 非空目录会在这里失败，错误信息由 translate 统一翻译
                channel.rmdir(path);
                return null;
            }
            deleteRecursive(channel, path, 0);
            return null;
        });
    }

    @Override
    public void upload(String connectionId, String path, InputStream inputStream, long size) {
        withChannel(connectionId, "上传", channel -> {
            channel.put(inputStream, path, ChannelSftp.OVERWRITE);
            return null;
        });
    }

    @Override
    public void download(String connectionId, String path, OutputStream outputStream) {
        withChannel(connectionId, "下载", channel -> {
            try (InputStream in = channel.get(path)) {
                byte[] buffer = new byte[32 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, len);
                }
                outputStream.flush();
            }
            return null;
        });
    }

    @Override
    public byte[] readPrefix(String connectionId, String path, int maxBytes) {
        return withChannel(connectionId, "读取", channel -> {
            try (InputStream in = channel.get(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(Math.max(maxBytes, 1), 64 * 1024));
                byte[] buffer = new byte[Math.min(Math.max(maxBytes, 1), 8192)];
                int remaining = Math.max(maxBytes, 1);
                while (remaining > 0) {
                    int len = in.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (len <= 0) {
                        break;
                    }
                    out.write(buffer, 0, len);
                    remaining -= len;
                }
                return out.toByteArray();
            }
        });
    }

    @Override
    public void write(String connectionId, String path, byte[] content) {
        withChannel(connectionId, "写入", channel -> {
            channel.put(new ByteArrayInputStream(content), path, ChannelSftp.OVERWRITE);
            return null;
        });
    }

    // ==================== 通道管理 ====================

    @FunctionalInterface
    private interface SftpAction<T> {
        T run(ChannelSftp channel) throws Exception;
    }

    /**
     * 打开通道 → 执行 → 关闭；异常统一翻译为带操作名的 AppException。
     */
    private <T> T withChannel(String connectionId, String operation, SftpAction<T> action) {
        Session session = sshSessionPort.getSession(connectionId);
        if (session == null || !session.isConnected()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(),
                    "SSH 连接未建立或已断开，无法执行文件操作: " + connectionId);
        }
        Object lock = channelLocks.computeIfAbsent(connectionId, key -> new Object());
        synchronized (lock) {
            ChannelSftp channel = null;
            try {
                channel = (ChannelSftp) session.openChannel("sftp");
                try {
                    channel.setFilenameEncoding(StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    // 部分服务端不支持 UTF-8 文件名协商，退化为默认编码
                }
                channel.connect(Math.max(securityProperties.getSftp().getTimeoutSeconds(), 1) * 1000);
                return action.run(channel);
            } catch (SftpException e) {
                throw translate(connectionId, operation, e);
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SFTP {} 失败 connectionId={} error={}", operation, connectionId, e.getMessage());
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "SFTP " + operation + "失败: " + e.getMessage());
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
            }
        }
    }

    private void deleteRecursive(ChannelSftp channel, String path, int depth) throws SftpException {
        if (depth > securityProperties.getSftp().getMaxDeleteDepth()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "目录层级超过删除上限 " + securityProperties.getSftp().getMaxDeleteDepth());
        }
        Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
        if (entries != null) {
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String child = path.endsWith("/") ? path + name : path + "/" + name;
                if (entry.getAttrs().isDir()) {
                    deleteRecursive(channel, child, depth + 1);
                } else {
                    channel.rm(child);
                }
            }
        }
        channel.rmdir(path);
    }

    // ==================== 转换 ====================

    private SftpFileVO toFileVO(String connectionId, ChannelSftp channel, String basePath,
                                ChannelSftp.LsEntry entry) {
        SftpATTRS attrs = entry.getAttrs();
        String name = entry.getFilename();
        String path = joinPath(basePath, name);
        return SftpFileVO.builder()
                .name(name)
                .path(path)
                .type(typeOf(attrs))
                .size(attrs.getSize())
                .modifiedAt(attrs.getMTime() * 1000L)
                .permissions(attrs.getPermissions())
                .permissionsText(attrs.getPermissionsString())
                .hidden(name.startsWith("."))
                .linkTarget(readLinkQuietly(channel, path, attrs))
                .owner(ownerOf(entry.getLongname()))
                .build();
    }

    private static SftpFileTypeEnum typeOf(SftpATTRS attrs) {
        if (attrs.isDir()) {
            return SftpFileTypeEnum.DIRECTORY;
        }
        if (attrs.isLink()) {
            return SftpFileTypeEnum.LINK;
        }
        if (attrs.isReg()) {
            return SftpFileTypeEnum.FILE;
        }
        return SftpFileTypeEnum.OTHER;
    }

    private static String readLinkQuietly(ChannelSftp channel, String path, SftpATTRS attrs) {
        if (!attrs.isLink()) {
            return null;
        }
        try {
            return channel.readlink(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** longname 例：drwxr-xr-x 2 root root 4096 Jan 1 00:00 bin → owner = root */
    private static String ownerOf(String longname) {
        if (longname == null) {
            return null;
        }
        String[] parts = longname.trim().split("\\s+");
        return parts.length >= 4 ? parts[2] : null;
    }

    private static String joinPath(String basePath, String name) {
        if ("/".equals(basePath)) {
            return "/" + name;
        }
        if (".".equals(basePath) || basePath.isEmpty()) {
            return name;
        }
        return basePath.endsWith("/") ? basePath + name : basePath + "/" + name;
    }

    private AppException translate(String connectionId, String operation, SftpException e) {
        String reason = switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE -> "文件或目录不存在";
            case ChannelSftp.SSH_FX_PERMISSION_DENIED -> "权限不足";
            case ChannelSftp.SSH_FX_NO_CONNECTION -> "SFTP 连接已断开";
            case ChannelSftp.SSH_FX_CONNECTION_LOST -> "连接丢失";
            case ChannelSftp.SSH_FX_OP_UNSUPPORTED -> "服务端不支持该操作";
            default -> e.getMessage();
        };
        log.warn("SFTP {} 失败 connectionId={} id={} reason={}", operation, connectionId, e.id, reason);
        return new AppException(ResponseCode.UN_ERROR.getCode(),
                "SFTP " + operation + "失败：" + reason);
    }
}

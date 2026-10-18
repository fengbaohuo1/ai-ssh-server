package jainingfei.site.ai.agent.infrastructure.adapter.port;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSch SSH 会话池——按 connectionId 维护长连接，供终端 / SFTP / AI 工具共享。
 *
 * <p>高级配置生效点：
 * <pre>
 *   connect-timeout     TCP 建连超时
 *   keepalive-interval  ServerAliveInterval（0 = 关闭）
 *   compression         zlib 双向压缩
 *   strict-host-key     仅在配置了 known-hosts 时启用（否则 JSch 首次连接必然失败；
 *                       未配置时回退 accept-all 并记录 WARN，避免"默认严格"把所有存量主机锁死）
 * </pre>
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    /** JSch 实例的 identity/known_hosts 注册不是线程安全的，建连串行化 */
    private final Object jschLock = new Object();

    @Override
    public boolean connect(String connectionId, String host, int port, String username,
                           String password, String privateKey) {
        return doConnect(connectionId, host, port, username, password, privateKey, null);
    }

    @Override
    public boolean connect(String connectionId, SshConnectionEntity connection,
                           SshConnectionConfigEntity config) {
        return doConnect(connectionId, connection.getHost(), connection.getPort(),
                connection.getUsername(), connection.getPassword(), connection.getPrivateKey(), config);
    }

    private boolean doConnect(String connectionId, String host, int port, String username,
                              String password, String privateKey, SshConnectionConfigEntity config) {
        if (connectionId == null || connectionId.isBlank()) {
            log.error("SSH连接失败：connectionId 为空");
            return false;
        }

        // 如果已连接，先断开旧连接
        disconnect(connectionId);

        int connectTimeoutSeconds = resolveConnectTimeout(config);
        try {
            Session session;
            synchronized (jschLock) {
                session = jsch.getSession(username, host, port);
                applyHostKeyPolicy(session, config);
                if (privateKey != null && !privateKey.isEmpty()) {
                    // 同一 connectionId 重连时先移除旧 identity，避免 JSch 内部累积同名私钥
                    try {
                        jsch.removeIdentity(connectionId);
                    } catch (JSchException ignored) {
                        // 未注册过
                    }
                    jsch.addIdentity(connectionId, privateKey.getBytes(StandardCharsets.UTF_8), null, null);
                }
            }

            // 认证方式：私钥优先，其次密码
            if (privateKey != null && !privateKey.isEmpty()) {
                session.setConfig("PreferredAuthentications", "publickey");
            } else if (password != null && !password.isEmpty()) {
                session.setPassword(password);
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            } else {
                log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
                return false;
            }

            applyAdvancedConfig(session, config);

            session.setTimeout(connectTimeoutSeconds * 1000);
            session.connect(connectTimeoutSeconds * 1000);

            sessions.put(connectionId, session);
            log.info("SSH连接成功 connectionId={} host={}:{} user={} timeout={}s keepalive={}s strictHostKey={}",
                    connectionId, host, port, username, connectTimeoutSeconds,
                    resolveKeepalive(config), resolveStrict(config));
            return true;
        } catch (JSchException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}",
                    connectionId, host, port, e.getMessage());
            return false;
        }
    }

    /** 主机密钥策略：严格模式只在 known-hosts 有配置时启用 */
    private void applyHostKeyPolicy(Session session, SshConnectionConfigEntity config) throws JSchException {
        boolean strict = Boolean.TRUE.equals(config != null ? config.getStrictHostKeyCheck() : null);
        String knownHosts = config == null ? null : config.getKnownHosts();
        boolean canVerify = strict && knownHosts != null && !knownHosts.isBlank();

        if (strict && !canVerify) {
            log.warn("已开启严格主机密钥校验但未配置 known-hosts，本次回退为 accept-all（建议补充 known-hosts）");
        }
        if (canVerify) {
            jsch.setKnownHosts(new ByteArrayInputStream(knownHosts.getBytes(StandardCharsets.UTF_8)));
        }
        session.setConfig("StrictHostKeyChecking", canVerify ? "yes" : "no");
    }

    private void applyAdvancedConfig(Session session, SshConnectionConfigEntity config) throws JSchException {
        if (config == null) {
            return;
        }
        int keepalive = resolveKeepalive(config);
        if (keepalive > 0) {
            session.setServerAliveInterval(keepalive * 1000);
            session.setServerAliveCountMax(3);
        }
        if (Boolean.TRUE.equals(config.getCompression())) {
            session.setConfig("compression.s2c", "zlib");
            session.setConfig("compression.c2s", "zlib");
        }
    }

    private static int resolveConnectTimeout(SshConnectionConfigEntity config) {
        Integer value = config == null ? null : config.getConnectTimeout();
        if (value == null || value < 1) {
            return 10;
        }
        return Math.min(value, 300);
    }

    private static int resolveKeepalive(SshConnectionConfigEntity config) {
        Integer value = config == null ? null : config.getKeepaliveInterval();
        if (value == null || value < 0) {
            return 60;
        }
        return value;
    }

    private static boolean resolveStrict(SshConnectionConfigEntity config) {
        return config != null && Boolean.TRUE.equals(config.getStrictHostKeyCheck())
                && config.getKnownHosts() != null && !config.getKnownHosts().isBlank();
    }

    @Override
    public void disconnect(String connectionId) {
        Session session = sessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.debug("SSH连接已断开 connectionId={}", connectionId);
        }
    }

    @Override
    public boolean isConnected(String connectionId) {
        Session session = sessions.get(connectionId);
        return session != null && session.isConnected();
    }

    @Override
    public Session getSession(String connectionId) {
        return sessions.get(connectionId);
    }

    @Override
    public int activeConnectionCount() {
        int count = 0;
        for (Session session : sessions.values()) {
            if (session != null && session.isConnected()) {
                count++;
            }
        }
        return count;
    }

}

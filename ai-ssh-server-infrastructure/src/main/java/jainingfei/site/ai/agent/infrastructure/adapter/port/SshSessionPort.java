package jainingfei.site.ai.agent.infrastructure.adapter.port;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassName:SshSessionPort
 * Project:ai-ssh-server
 * Package: jainingfei.site.ai.agent.infrastructure.adapter.port
 *
 * @Author 贾宁飞
 * @Description
 * @Create 2026/7/1 下午5:46
 * @Version 1.0
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    @Override
    public boolean connect(String connectionId, String host, int port, String username, String password, String privateKey) {
        // 如果已连接，先断开旧连接
        disconnect(connectionId);

        try {
            // 1. 创建会话
            Session session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(30000); // 30秒超时

            // 2. 设置认证方式
            if (privateKey != null && !privateKey.isEmpty()) {
                // 私钥认证
                jsch.addIdentity(connectionId, privateKey.getBytes(), null, null);
            } else if (password != null && !password.isEmpty()) {
                // 密码认证
                session.setPassword(password);
            } else {
                log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
                return false;
            }

            // 3. 建立连接
            session.connect();
            sessions.put(connectionId, session);
            log.info("SSH连接成功 connectionId={} host={}:{} user={}",
                    connectionId, host, port, username);
            return true;
        } catch (JSchException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}",
                    connectionId, host, port, e.getMessage());
            return false;
        }
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

    /**
     * 获取会话
     *
     * @param connectionId 连接ID
     * @return JSch Session
     */
    public Session getSession(String connectionId) {
        return sessions.get(connectionId);
    }

}

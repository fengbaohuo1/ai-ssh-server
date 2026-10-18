package jainingfei.site.ai.agent.domain.ssh.adapter.port;

import com.jcraft.jsch.Session;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;

/**
 * ClassName:ISshSessionPort
 * Project:ai-ssh-server
 * Package: jainingfei.site.ai.agent.domain.ssh.adapter.port
 *
 * @Author 贾宁飞
 * @Description
 * @Create 2026/7/1 下午5:45
 * @Version 1.0
 */
public interface ISshSessionPort {
    /**
     * 建立 SSH 连接
     *
     * @param connectionId 连接ID
     * @param host         主机地址
     * @param port         端口
     * @param username     用户名
     * @param password     密码
     * @param privateKey   私钥
     * @return 是否连接成功
     */
    boolean connect(String connectionId, String host, int port, String username,
                    String password, String privateKey);

    /**
     * 带高级配置建立连接（超时 / 保活 / 压缩 / 严格主机密钥）。
     * <p>默认实现退回基础签名，便于替换实现平滑迁移。
     */
    default boolean connect(String connectionId, SshConnectionEntity connection,
                            SshConnectionConfigEntity config) {
        return connect(connectionId, connection.getHost(), connection.getPort(),
                connection.getUsername(), connection.getPassword(), connection.getPrivateKey());
    }

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    void disconnect(String connectionId);

    /**
     * 检查是否已连接
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    boolean isConnected(String connectionId);

    /**
     * 获取 JSch Session（供 WebSocket 终端使用）
     *
     * @param connectionId 连接ID
     * @return JSch Session，可能为 null
     */
    Session getSession(String connectionId);

    /**
     * 当前已建立的 SSH 会话数（运维概览 / 健康检查使用）
     *
     * @return 活跃会话数量
     */
    default int activeConnectionCount() {
        return 0;
    }
}

package jainingfei.site.ai.agent.domain.ssh.service.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.repository.ISshConnectionRepository;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SSH 连接领域服务实现
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionService {

    @Resource
    private ISshConnectionRepository sshConnectionRepository;

    @Resource
    private ISshSessionPort sshSessionPort;

    @Override
    public SshConnectionAggregate createConnection(SshConnectionEntity connection, SshConnectionConfigEntity config) {
        log.info("创建SSH连接配置 connectionName={} host={}", connection.getConnectionName(), connection.getHost());

        // 默认连接状态为未连接
        connection.setStatus(ConnectionStatusEnum.DISCONNECTED);

        // 保存基础连接信息
        sshConnectionRepository.save(connection);

        // 保存高级配置
        if (config != null) {
            config.setConnectionId(connection.getConnectionId());
            sshConnectionRepository.saveConfig(config);
        }

        return SshConnectionAggregate.builder()
                .connection(connection)
                .config(config)
                .build();
    }

    @Override
    public boolean connect(String connectionId) {
        log.info("建立SSH连接 connectionId={}", connectionId);

        // 1. 查询连接信息
        SshConnectionEntity connection = sshConnectionRepository.queryByConnectionId(connectionId).getConnection();
        if (connection == null) {
            log.error("连接不存在 connectionId={}", connectionId);
            return false;
        }

        // 2. 更新状态为连接中
        sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.CONNECTING);

        try {
            // 3. 建立 SSH 连接
            boolean connected = sshSessionPort.connect(
                    connectionId,
                    connection.getHost(),
                    connection.getPort(),
                    connection.getUsername(),
                    connection.getPassword(),
                    connection.getPrivateKey()
            );

            // 4. 更新连接状态
            ConnectionStatusEnum newStatus = connected ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED;
            sshConnectionRepository.updateStatus(connectionId, newStatus);

            return connected;
        } catch (Exception e) {
            log.error("SSH连接异常 connectionId={}", connectionId, e);
            sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.FAILED);
            return false;
        }
    }

    @Override
    public void disconnect(String connectionId) {
        log.info("断开SSH连接 connectionId={}", connectionId);

        // 1. 断开 SSH 会话
        sshSessionPort.disconnect(connectionId);

        // 2. 更新状态
        sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.DISCONNECTED);
    }

    @Override
    public List<SshConnectionEntity> queryUserConnections(String userId) {
        return sshConnectionRepository.queryListByUserId(userId);
    }

    @Override
    public SshConnectionAggregate queryConnectionDetail(String connectionId) {
        return sshConnectionRepository.queryByConnectionId(connectionId);
    }

    @Override
    public void deleteConnection(String connectionId) {
        log.info("删除SSH连接 connectionId={}", connectionId);

        // 先断开连接
        sshSessionPort.disconnect(connectionId);

        // 软删除
        sshConnectionRepository.deleteByConnectionId(connectionId);
    }
}

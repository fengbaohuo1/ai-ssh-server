package jainingfei.site.ai.agent.domain.ssh.service.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.adapter.port.ICredentialCipher;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import jainingfei.site.ai.agent.domain.security.support.AuditContext;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.repository.ISshConnectionRepository;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SSH 连接领域服务实现。
 *
 * <p>安全职责：
 * <ul>
 *   <li>创建 / 更新时用 {@link ICredentialCipher} 加密密码与私钥，禁止明文落库</li>
 *   <li>建立连接前解密；解密失败给出明确提示（主密钥不匹配需重新录入）</li>
 *   <li>连接生命周期与凭证变更写入审计</li>
 * </ul>
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionService {

    @Resource
    private ISshConnectionRepository sshConnectionRepository;

    @Resource
    private ISshSessionPort sshSessionPort;

    @Resource
    private ICredentialCipher credentialCipher;

    @Resource
    private IAuditService auditService;

    @Override
    public SshConnectionAggregate createConnection(SshConnectionEntity connection, SshConnectionConfigEntity config) {
        log.info("创建SSH连接配置 connectionName={} host={}", connection.getConnectionName(), connection.getHost());

        connection.setStatus(ConnectionStatusEnum.DISCONNECTED);
        normalizeCredentials(connection);
        encryptCredentials(connection);
        sshConnectionRepository.save(connection);

        if (config != null) {
            config.setConnectionId(connection.getConnectionId());
            sshConnectionRepository.saveConfig(config);
        }

        auditService.record(AuditContext.userId(), connection.getConnectionId(),
                connection.getConnectionName(), AuditActionEnum.CONNECTION_CREATE,
                connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort(),
                "认证方式=" + (connection.getAuthType() == null ? "-" : connection.getAuthType().getDesc())
                        + "；分组=" + safe(connection.getGroupName()),
                null, true, 0L, AuditContext.clientIp());

        return SshConnectionAggregate.builder()
                .connection(connection)
                .config(config)
                .build();
    }

    @Override
    public SshConnectionAggregate updateConnection(String connectionId, SshConnectionEntity patch,
                                                   SshConnectionConfigEntity configPatch) {
        SshConnectionAggregate aggregate = sshConnectionRepository.queryByConnectionId(connectionId);
        if (aggregate == null || aggregate.getConnection() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "连接不存在: " + connectionId);
        }
        SshConnectionEntity current = aggregate.getConnection();

        // 基础字段：null 表示"不修改"（与前端部分更新语义一致）
        if (patch.getConnectionName() != null) current.setConnectionName(patch.getConnectionName());
        if (patch.getHost() != null) current.setHost(patch.getHost());
        if (patch.getPort() != null) current.setPort(patch.getPort());
        if (patch.getUsername() != null) current.setUsername(patch.getUsername());
        if (patch.getAuthType() != null) current.setAuthType(patch.getAuthType());
        if (patch.getGroupName() != null) current.setGroupName(patch.getGroupName());
        if (patch.getTags() != null) current.setTags(patch.getTags());
        if (patch.getRemark() != null) current.setRemark(patch.getRemark());
        if (patch.getFavorite() != null) current.setFavorite(patch.getFavorite());

        // 凭证：非空才更新并加密；空串表示保持原值（前端不回显密码）
        boolean credentialChanged = false;
        if (patch.getPassword() != null && !patch.getPassword().isEmpty()) {
            current.setPassword(credentialCipher.encrypt(patch.getPassword()));
            current.setEncrypted(true);
            credentialChanged = true;
        }
        if (patch.getPrivateKey() != null && !patch.getPrivateKey().isEmpty()) {
            current.setPrivateKey(credentialCipher.encrypt(patch.getPrivateKey()));
            current.setEncrypted(true);
            credentialChanged = true;
        }
        current.setUpdatedAt(LocalDateTime.now());
        // 认证方式切换后清掉另一类凭证，避免"同时存在密码与私钥"时误用旧凭证
        normalizeCredentials(current);

        validate(current);
        sshConnectionRepository.save(current);

        SshConnectionConfigEntity config = aggregate.getConfig();
        if (config == null && configPatch != null) {
            config = SshConnectionConfigEntity.builder().connectionId(connectionId).build();
        }
        if (configPatch != null && config != null) {
            if (configPatch.getConnectTimeout() != null) config.setConnectTimeout(configPatch.getConnectTimeout());
            if (configPatch.getKeepaliveInterval() != null) config.setKeepaliveInterval(configPatch.getKeepaliveInterval());
            if (configPatch.getStartupCommand() != null) config.setStartupCommand(configPatch.getStartupCommand());
            if (configPatch.getCompression() != null) config.setCompression(configPatch.getCompression());
            if (configPatch.getStrictHostKeyCheck() != null) config.setStrictHostKeyCheck(configPatch.getStrictHostKeyCheck());
            if (configPatch.getKnownHosts() != null) config.setKnownHosts(configPatch.getKnownHosts());
            config.setUpdatedAt(LocalDateTime.now());
            sshConnectionRepository.saveConfig(config);
        }

        auditService.record(AuditContext.userId(), connectionId, current.getConnectionName(),
                AuditActionEnum.CONNECTION_UPDATE,
                current.getUsername() + "@" + current.getHost() + ":" + current.getPort(),
                credentialChanged ? "凭证已更新" : "属性已更新",
                null, true, 0L, AuditContext.clientIp());

        return SshConnectionAggregate.builder().connection(current).config(config).build();
    }

    @Override
    public boolean connect(String connectionId) {
        log.debug("建立SSH连接 connectionId={}", connectionId);

        SshConnectionAggregate aggregate = sshConnectionRepository.queryByConnectionId(connectionId);
        SshConnectionEntity connection = aggregate == null ? null : aggregate.getConnection();
        if (connection == null) {
            log.error("连接不存在 connectionId={}", connectionId);
            return false;
        }

        validate(connection);
        sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.CONNECTING);

        long start = System.currentTimeMillis();
        try {
            // 只解密当前认证方式对应的凭证：历史数据可能同时残留密码与私钥
            boolean useKey = connection.getAuthType() == AuthTypeEnum.PRIVATE_KEY
                    || (connection.getAuthType() == null
                        && connection.getPrivateKey() != null && !connection.getPrivateKey().isEmpty());
            String password = useKey ? null : credentialCipher.decrypt(connection.getPassword());
            String privateKey = useKey ? credentialCipher.decrypt(connection.getPrivateKey()) : null;
            // 只在内存中把明文交给连接适配器，绝不写回数据库
            connection.setPassword(password);
            connection.setPrivateKey(privateKey);

            boolean connected = sshSessionPort.connect(connectionId, connection, aggregate.getConfig());

            ConnectionStatusEnum newStatus = connected
                    ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED;
            sshConnectionRepository.updateStatus(connectionId, newStatus);
            if (connected) {
                sshConnectionRepository.updateLastConnectedAt(connectionId, LocalDateTime.now());
            }

            auditService.record(AuditContext.userId(), connectionId, connection.getConnectionName(),
                    AuditActionEnum.CONNECTION_CONNECT,
                    connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort(),
                    connected ? null : "认证失败或网络不可达",
                    null, connected, System.currentTimeMillis() - start, AuditContext.clientIp());
            return connected;
        } catch (IllegalStateException e) {
            // 凭证解密失败：状态置失败并上抛，让界面给出可操作提示
            sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.FAILED);
            auditService.record(AuditContext.userId(), connectionId, connection.getConnectionName(),
                    AuditActionEnum.CONNECTION_CONNECT, connection.getUsername() + "@" + connection.getHost(),
                    e.getMessage(), null, false, System.currentTimeMillis() - start, AuditContext.clientIp());
            throw new AppException(ResponseCode.UN_ERROR.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("SSH连接异常 connectionId={}", connectionId, e);
            sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.FAILED);
            auditService.record(AuditContext.userId(), connectionId, connection.getConnectionName(),
                    AuditActionEnum.CONNECTION_CONNECT, connection.getHost(),
                    e.getMessage(), null, false, System.currentTimeMillis() - start, AuditContext.clientIp());
            return false;
        }
    }

    @Override
    public void disconnect(String connectionId) {
        log.info("断开SSH连接 connectionId={}", connectionId);
        SshConnectionAggregate aggregate = sshConnectionRepository.queryByConnectionId(connectionId);
        sshSessionPort.disconnect(connectionId);
        sshConnectionRepository.updateStatus(connectionId, ConnectionStatusEnum.DISCONNECTED);

        auditService.record(AuditContext.userId(), connectionId,
                aggregate == null || aggregate.getConnection() == null
                        ? null : aggregate.getConnection().getConnectionName(),
                AuditActionEnum.CONNECTION_DISCONNECT, connectionId, null,
                null, true, 0L, AuditContext.clientIp());
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

        SshConnectionAggregate aggregate = sshConnectionRepository.queryByConnectionId(connectionId);
        String name = aggregate == null || aggregate.getConnection() == null
                ? null : aggregate.getConnection().getConnectionName();

        sshSessionPort.disconnect(connectionId);
        sshConnectionRepository.deleteByConnectionId(connectionId);

        auditService.record(AuditContext.userId(), connectionId, name,
                AuditActionEnum.CONNECTION_DELETE, connectionId, null,
                null, true, 0L, AuditContext.clientIp());
    }

    // ==================== 内部 ====================

    /** 加密非空凭证（幂等：已是密文不重复加密） */
    private void encryptCredentials(SshConnectionEntity connection) {
        if (connection.getPassword() != null && !connection.getPassword().isEmpty()) {
            connection.setPassword(credentialCipher.encrypt(connection.getPassword()));
            connection.setEncrypted(true);
        }
        if (connection.getPrivateKey() != null && !connection.getPrivateKey().isEmpty()) {
            connection.setPrivateKey(credentialCipher.encrypt(connection.getPrivateKey()));
            connection.setEncrypted(true);
        }
    }

    /** 认证方式与凭证一一对应：密码认证清空私钥，私钥认证清空密码。
     *  用空串而不是 null：MyBatis-Plus 默认忽略 null 字段，空串才能把旧凭证真正从库里清掉。 */
    private static void normalizeCredentials(SshConnectionEntity connection) {
        if (connection.getAuthType() == AuthTypeEnum.PASSWORD) {
            connection.setPrivateKey("");
        } else if (connection.getAuthType() == AuthTypeEnum.PRIVATE_KEY) {
            connection.setPassword("");
        }
    }

    /** 基础合法性校验——领域层兜底，避免脏数据落库 */
    private static void validate(SshConnectionEntity connection) {
        if (connection.getConnectionName() == null || connection.getConnectionName().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "连接名称不能为空");
        }
        if (connection.getHost() == null || connection.getHost().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "主机地址不能为空");
        }
        if (connection.getPort() == null || connection.getPort() < 1 || connection.getPort() > 65535) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "端口必须在 1-65535 之间");
        }
        if (connection.getUsername() == null || connection.getUsername().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户名不能为空");
        }
        if (connection.getGroupName() != null && connection.getGroupName().length() > 64) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "分组名称过长（上限 64 字符）");
        }
        if (connection.getTags() != null && connection.getTags().size() > 20) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "标签数量过多（上限 20 个）");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}

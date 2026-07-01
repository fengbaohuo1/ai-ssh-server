package jainingfei.site.ai.agent.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.adapter.repository.ISshConnectionRepository;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.infrastructure.dao.ISshConnectionConfigDao;
import jainingfei.site.ai.agent.infrastructure.dao.ISshConnectionDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.SshConnectionConfigPO;
import jainingfei.site.ai.agent.infrastructure.dao.po.SshConnectionPO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SshConnectionRepository implements ISshConnectionRepository {

    @Resource
    private ISshConnectionDao sshConnectionDao;

    @Resource
    private ISshConnectionConfigDao sshConnectionConfigDao;

    @Override
    public SshConnectionAggregate queryByConnectionId(String connectionId) {
        // 查询基础连接
        SshConnectionPO connectionPO = sshConnectionDao.selectOne(
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connectionId)
        );
        if (connectionPO == null) {
            return null;
        }

        // 查询高级配置
        SshConnectionConfigPO configPO = sshConnectionConfigDao.selectOne(
                new LambdaQueryWrapper<SshConnectionConfigPO>()
                        .eq(SshConnectionConfigPO::getConnectionId, connectionId)
        );

        return SshConnectionAggregate.builder()
                .connection(toEntity(connectionPO))
                .config(toConfigEntity(configPO))
                .build();
    }

    @Override
    public List<SshConnectionEntity> queryListByUserId(String userId) {
        List<SshConnectionPO> poList = sshConnectionDao.selectList(
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getUserId, userId)
                        .orderByDesc(SshConnectionPO::getCreatedAt)
        );
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        return poList.stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void save(SshConnectionEntity connection) {
        SshConnectionPO po = toPO(connection);

        // 判断新增还是更新
        SshConnectionPO existPO = sshConnectionDao.selectOne(
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connection.getConnectionId())
        );

        if (existPO != null) {
            po.setId(existPO.getId());
            sshConnectionDao.updateById(po);
        } else {
            sshConnectionDao.insert(po);
        }
    }

    @Override
    public void saveConfig(SshConnectionConfigEntity config) {
        SshConnectionConfigPO po = toPO(config);

        SshConnectionConfigPO existPO = sshConnectionConfigDao.selectOne(
                new LambdaQueryWrapper<SshConnectionConfigPO>()
                        .eq(SshConnectionConfigPO::getConnectionId, config.getConnectionId())
        );

        if (existPO != null) {
            po.setId(existPO.getId());
            sshConnectionConfigDao.updateById(po);
        } else {
            sshConnectionConfigDao.insert(po);
        }
    }

    @Override
    public void updateStatus(String connectionId, ConnectionStatusEnum status) {
        SshConnectionPO updatePO = new SshConnectionPO();
        updatePO.setStatus(status.getCode());

        sshConnectionDao.update(
                updatePO,
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connectionId)
        );
    }

    @Override
    public void deleteByConnectionId(String connectionId) {
        // MyBatis-Plus 逻辑删除：@TableLogic 标注的字段自动设置为删除值
        sshConnectionDao.delete(
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connectionId)
        );
    }

    // ==================== 转换方法 ====================

    private SshConnectionEntity toEntity(SshConnectionPO po) {
        if (po == null) return null;
        return SshConnectionEntity.builder()
                .connectionId(po.getConnectionId())
                .connectionName(po.getConnectionName())
                .host(po.getHost())
                .port(po.getPort())
                .username(po.getUsername())
                .authType(AuthTypeEnum.fromCode(po.getAuthType()))
                .password(po.getPassword())
                .privateKey(po.getPrivateKey())
                .encrypted(intToBool(po.getEncrypted()))
                .status(ConnectionStatusEnum.fromCode(po.getStatus()))
                .userId(po.getUserId())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .deleted(intToBool(po.getDeleted()))
                .build();
    }

    private SshConnectionPO toPO(SshConnectionEntity entity) {
        if (entity == null) return null;
        SshConnectionPO po = new SshConnectionPO();
        po.setConnectionId(entity.getConnectionId());
        po.setConnectionName(entity.getConnectionName());
        po.setHost(entity.getHost());
        po.setPort(entity.getPort());
        po.setUsername(entity.getUsername());
        po.setAuthType(entity.getAuthType() != null ? entity.getAuthType().getCode() : null);
        po.setPassword(entity.getPassword());
        po.setPrivateKey(entity.getPrivateKey());
        po.setEncrypted(boolToInt(entity.getEncrypted()));
        po.setStatus(entity.getStatus() != null ? entity.getStatus().getCode() : null);
        po.setUserId(entity.getUserId());
        po.setCreatedAt(entity.getCreatedAt());
        po.setUpdatedAt(entity.getUpdatedAt());
        po.setDeleted(boolToInt(entity.getDeleted()));
        return po;
    }

    private SshConnectionConfigEntity toConfigEntity(SshConnectionConfigPO po) {
        if (po == null) return null;
        return SshConnectionConfigEntity.builder()
                .connectionId(po.getConnectionId())
                .connectTimeout(po.getConnectTimeout())
                .keepaliveInterval(po.getKeepaliveInterval())
                .startupCommand(po.getStartupCommand())
                .compression(intToBool(po.getCompression()))
                .strictHostKeyCheck(intToBool(po.getStrictHostKeyCheck()))
                .knownHosts(po.getKnownHosts())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private SshConnectionConfigPO toPO(SshConnectionConfigEntity entity) {
        if (entity == null) return null;
        SshConnectionConfigPO po = new SshConnectionConfigPO();
        po.setConnectionId(entity.getConnectionId());
        po.setConnectTimeout(entity.getConnectTimeout());
        po.setKeepaliveInterval(entity.getKeepaliveInterval());
        po.setStartupCommand(entity.getStartupCommand());
        po.setCompression(boolToInt(entity.getCompression()));
        po.setStrictHostKeyCheck(boolToInt(entity.getStrictHostKeyCheck()));
        po.setKnownHosts(entity.getKnownHosts());
        po.setUpdatedAt(entity.getUpdatedAt());
        return po;
    }

    private Boolean intToBool(Integer value) {
        return value != null ? value == 1 : null;
    }

    private Integer boolToInt(Boolean value) {
        return value != null ? (value ? 1 : 0) : null;
    }
}

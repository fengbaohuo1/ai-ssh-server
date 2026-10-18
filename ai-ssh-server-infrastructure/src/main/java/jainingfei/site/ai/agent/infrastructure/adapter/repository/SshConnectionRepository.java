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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
            // 本地 H2 无 ON UPDATE 语义：更新路径必须显式带时间，避免界面看到"永不变化"的更新时间
            if (po.getUpdatedAt() == null) {
                po.setUpdatedAt(LocalDateTime.now());
            }
            if (po.getCreatedAt() == null) {
                po.setCreatedAt(existPO.getCreatedAt());
            }
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
            if (po.getUpdatedAt() == null) {
                po.setUpdatedAt(LocalDateTime.now());
            }
            sshConnectionConfigDao.updateById(po);
        } else {
            sshConnectionConfigDao.insert(po);
        }
    }

    @Override
    public void updateStatus(String connectionId, ConnectionStatusEnum status) {
        // updated_at 显式写 Java 时间：本地 H2 库没有 MySQL 的 ON UPDATE CURRENT_TIMESTAMP
        SshConnectionPO updatePO = new SshConnectionPO();
        updatePO.setStatus(status.getCode());
        updatePO.setUpdatedAt(LocalDateTime.now());

        sshConnectionDao.update(
                updatePO,
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connectionId)
        );
    }

    @Override
    public void updateLastConnectedAt(String connectionId, LocalDateTime lastConnectedAt) {
        SshConnectionPO updatePO = new SshConnectionPO();
        updatePO.setLastConnectedAt(lastConnectedAt);
        updatePO.setUpdatedAt(LocalDateTime.now());
        sshConnectionDao.update(updatePO,
                new LambdaQueryWrapper<SshConnectionPO>()
                        .eq(SshConnectionPO::getConnectionId, connectionId));
    }

    @Override
    public long countByUserId(String userId) {
        Long count = sshConnectionDao.selectCount(
                new LambdaQueryWrapper<SshConnectionPO>().eq(SshConnectionPO::getUserId, userId));
        return count == null ? 0L : count;
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
                .groupName(po.getGroupName())
                .tags(parseTags(po.getTags()))
                .remark(po.getRemark())
                .favorite(intToBool(po.getFavorite()))
                .lastConnectedAt(po.getLastConnectedAt())
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
        po.setGroupName(entity.getGroupName());
        po.setTags(joinTags(entity.getTags()));
        po.setRemark(entity.getRemark());
        po.setFavorite(boolToInt(entity.getFavorite()));
        po.setLastConnectedAt(entity.getLastConnectedAt());
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

    /** 逗号分隔标签 → 列表（去空、去重、保序） */
    private static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** 列表 → 逗号分隔标签 */
    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }
}

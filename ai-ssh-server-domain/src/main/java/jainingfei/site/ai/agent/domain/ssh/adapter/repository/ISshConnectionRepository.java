package jainingfei.site.ai.agent.domain.ssh.adapter.repository;

import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;

import java.util.List;

/**
 * SSH 连接仓储接口
 */
public interface ISshConnectionRepository {

    /**
     * 根据连接 ID 查询完整连接（含高级配置）
     */
    SshConnectionAggregate queryByConnectionId(String connectionId);

    /**
     * 查询用户的所有连接列表
     */
    List<SshConnectionEntity> queryListByUserId(String userId);

    /**
     * 保存连接信息（新增/更新）
     */
    void save(SshConnectionEntity connection);

    /**
     * 保存高级配置（新增/更新）
     */
    void saveConfig(SshConnectionConfigEntity config);

    /**
     * 更新连接状态
     */
    void updateStatus(String connectionId, ConnectionStatusEnum status);

    /**
     * 删除连接（软删除）
     */
    void deleteByConnectionId(String connectionId);
}

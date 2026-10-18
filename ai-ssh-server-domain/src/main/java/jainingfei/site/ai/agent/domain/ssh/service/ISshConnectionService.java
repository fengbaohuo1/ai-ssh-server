package jainingfei.site.ai.agent.domain.ssh.service;

import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;

import java.util.List;

/**
 * SSH 连接领域服务接口
 */
public interface ISshConnectionService {

    /**
     * 创建 SSH 连接配置
     *
     * @param connection 基础连接信息
     * @param config     高级配置
     * @return 完整的连接聚合
     */
    SshConnectionAggregate createConnection(SshConnectionEntity connection, SshConnectionConfigEntity config);

    /**
     * 更新连接配置（部分更新：null = 不修改；凭证空串 = 保持原值）。
     *
     * @param connectionId 连接 ID
     * @param patch        基础字段补丁
     * @param configPatch  高级配置补丁
     * @return 更新后的聚合
     */
    SshConnectionAggregate updateConnection(String connectionId, SshConnectionEntity patch,
                                            SshConnectionConfigEntity configPatch);

    /**
     * 建立 SSH 连接
     *
     * @param connectionId 连接 ID
     * @return 是否连接成功
     */
    boolean connect(String connectionId);

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接 ID
     */
    void disconnect(String connectionId);

    /**
     * 查询用户的所有连接列表
     *
     * @param userId 用户 ID
     * @return 连接列表（不含高级配置）
     */
    List<SshConnectionEntity> queryUserConnections(String userId);

    /**
     * 查询连接完整详情
     *
     * @param connectionId 连接 ID
     * @return 连接聚合（含高级配置）
     */
    SshConnectionAggregate queryConnectionDetail(String connectionId);

    /**
     * 删除连接（软删除）
     *
     * @param connectionId 连接 ID
     */
    void deleteConnection(String connectionId);
}

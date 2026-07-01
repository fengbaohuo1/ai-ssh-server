package jainingfei.site.ai.agent.domain.ssh.model.aggregate;

import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH 连接聚合 — 聚合基础连接信息和高级配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConnectionAggregate {

    /** 基础连接信息 + 认证凭证 + 系统管理信息 */
    private SshConnectionEntity connection;

    /** 高级配置信息 */
    private SshConnectionConfigEntity config;

}

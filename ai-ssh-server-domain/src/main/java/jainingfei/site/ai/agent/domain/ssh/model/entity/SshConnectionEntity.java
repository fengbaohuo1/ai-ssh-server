package jainingfei.site.ai.agent.domain.ssh.model.entity;

import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH 连接实体 — 基础连接信息 + 认证凭证 + 系统管理信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConnectionEntity {

    /** 连接唯一标识 (UUID) */
    private String connectionId;

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口号 */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证方式 */
    private AuthTypeEnum authType;

    /** 密码（加密存储） */
    private String password;

    /** 私钥内容（加密存储） */
    private String privateKey;

    /** 是否加密 */
    private Boolean encrypted;

    /** 连接状态 */
    private ConnectionStatusEnum status;

    /** 用户ID */
    private String userId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记 */
    private Boolean deleted;

}

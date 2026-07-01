package jainingfei.site.ai.agent.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH 连接高级配置实体 — 低频访问字段，独立表存储
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConnectionConfigEntity {

    /** 关联的连接 ID */
    private String connectionId;

    /** 连接超时时间（秒） */
    private Integer connectTimeout;

    /** 保活间隔（秒） */
    private Integer keepaliveInterval;

    /** 连接后执行的启动命令 */
    private String startupCommand;

    /** 是否启用压缩 */
    private Boolean compression;

    /** 是否严格检查主机密钥 */
    private Boolean strictHostKeyCheck;

    /** 已知主机密钥列表 */
    private String knownHosts;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

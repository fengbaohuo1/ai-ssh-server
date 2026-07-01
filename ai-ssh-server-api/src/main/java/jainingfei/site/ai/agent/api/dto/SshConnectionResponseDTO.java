package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SSH 连接响应 DTO
 */
@Data
public class SshConnectionResponseDTO {

    /** 连接唯一标识 */
    private String connectionId;

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口号 */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 连接状态: 0-未连接, 1-已连接, 2-连接中, 3-连接失败 */
    private Integer status;

    /** 用户 ID */
    private String userId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    // ====== 高级配置 ======

    /** 连接超时时间（秒） */
    private Integer connectTimeout;

    /** 保活间隔（秒） */
    private Integer keepaliveInterval;

    /** 连接后执行的启动命令 */
    private String startupCommand;

    /** 是否启用压缩 */
    private Integer compression;

    /** 是否严格检查主机密钥 */
    private Integer strictHostKeyCheck;

    /** 已知主机密钥列表 */
    private String knownHosts;
}

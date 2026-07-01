package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * SSH 连接创建请求 DTO
 */
@Data
public class SshConnectionCreateRequestDTO {

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口号（默认 22） */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 密码 */
    private String password;

    /** 私钥内容 */
    private String privateKey;

    /** 用户 ID */
    private String userId;

    // ====== 高级配置（可选） ======

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

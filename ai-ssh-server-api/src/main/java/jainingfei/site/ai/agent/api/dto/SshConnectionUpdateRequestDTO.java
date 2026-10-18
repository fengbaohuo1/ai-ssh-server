package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * SSH 连接更新请求 DTO——部分更新语义：
 * 字段为 null 表示不修改；password / privateKey 传空串表示保持原凭证。
 */
@Data
public class SshConnectionUpdateRequestDTO {

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口 */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 新密码（空 = 不修改） */
    private String password;

    /** 新私钥（空 = 不修改） */
    private String privateKey;

    /** 分组 */
    private String groupName;

    /** 标签 */
    private List<String> tags;

    /** 备注 */
    private String remark;

    /** 是否收藏 */
    private Boolean favorite;

    // ====== 高级配置 ======

    private Integer connectTimeout;
    private Integer keepaliveInterval;
    private String startupCommand;
    private Integer compression;
    private Integer strictHostKeyCheck;
    private String knownHosts;
}

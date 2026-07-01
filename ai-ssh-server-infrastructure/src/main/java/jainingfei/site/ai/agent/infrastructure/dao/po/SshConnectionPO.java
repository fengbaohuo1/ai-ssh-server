package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SSH 连接持久化对象 — 对应表 ssh_connection
 */
@Data
@TableName("ssh_connection")
public class SshConnectionPO {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 密码 (加密存储) */
    private String password;

    /** 私钥内容 (加密存储) */
    private String privateKey;

    /** 是否加密: 0-否, 1-是 */
    private Integer encrypted;

    /** 连接状态: 0-未连接, 1-已连接, 2-连接中, 3-连接失败 */
    private Integer status;

    /** 用户 ID */
    private String userId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除: 0-未删除, 1-已删除 */
    @TableLogic
    private Integer deleted;

}

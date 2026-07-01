package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SSH 连接高级配置持久化对象 — 对应表 ssh_connection_config
 */
@Data
@TableName("ssh_connection_config")
public class SshConnectionConfigPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的连接 ID */
    private String connectionId;

    /** 连接超时时间 (秒) */
    private Integer connectTimeout;

    /** 保活间隔 (秒) */
    private Integer keepaliveInterval;

    /** 连接后执行的启动命令 */
    private String startupCommand;

    /** 是否压缩: 0-否, 1-是 */
    private Integer compression;

    /** 严格主机密钥检查: 0-否, 1-是 */
    private Integer strictHostKeyCheck;

    /** 已知主机密钥列表 */
    private String knownHosts;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计持久化对象 — 对应表 operation_audit_log
 */
@Data
@TableName("operation_audit_log")
public class AuditLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户 */
    private String userId;

    /** 连接 ID */
    private String connectionId;

    /** 连接名快照 */
    private String connectionName;

    /** 动作名（AuditActionEnum.name()） */
    private String action;

    /** 资源 */
    private String resource;

    /** 明细 */
    private String detail;

    /** 风险等级（RiskLevelEnum.name()） */
    private String riskLevel;

    /** 是否成功: 0/1 */
    private Integer success;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 客户端 IP */
    private String clientIp;

    /** 发生时间 */
    private LocalDateTime createdAt;
}

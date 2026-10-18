package jainingfei.site.ai.agent.domain.security.model.entity;

import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.RiskLevelEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作审计实体——不可变事实记录：谁、在哪个连接、做了什么、是否成功、耗时多少。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntity {

    /** 自增主键 */
    private Long id;

    /** 操作用户（当前单用户体系默认 default） */
    private String userId;

    /** 关联的 SSH 连接 ID（可为空，如纯系统操作） */
    private String connectionId;

    /** 连接名称快照（连接删除后仍可读） */
    private String connectionName;

    /** 动作 */
    private AuditActionEnum action;

    /** 资源（命令 / 文件路径 / 会话 ID） */
    private String resource;

    /** 明细（截断后的说明） */
    private String detail;

    /** 风险等级 */
    private RiskLevelEnum riskLevel;

    /** 是否成功 */
    private Boolean success;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 客户端 IP */
    private String clientIp;

    /** 发生时间 */
    private LocalDateTime createdAt;
}

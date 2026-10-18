package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志响应 DTO。
 */
@Data
public class AuditLogResponseDTO {

    private Long id;

    private String userId;

    private String connectionId;

    private String connectionName;

    /** 动作枚举名（前端可据此映射图标） */
    private String action;

    /** 动作中文名 */
    private String actionLabel;

    private String resource;

    private String detail;

    /** LOW / MEDIUM / HIGH / CRITICAL */
    private String riskLevel;

    /** 风险中文名 */
    private String riskLabel;

    private Boolean success;

    private Long durationMs;

    private String clientIp;

    private LocalDateTime createdAt;
}

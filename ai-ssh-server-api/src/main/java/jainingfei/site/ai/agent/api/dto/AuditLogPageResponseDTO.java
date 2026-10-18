package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 审计日志分页响应 DTO。
 */
@Data
public class AuditLogPageResponseDTO {

    private List<AuditLogResponseDTO> list;

    private long total;

    private int limit;

    private int offset;
}

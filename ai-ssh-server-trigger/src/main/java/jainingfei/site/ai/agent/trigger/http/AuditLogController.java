package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.AuditActionInfoDTO;
import jainingfei.site.ai.agent.api.dto.AuditLogPageResponseDTO;
import jainingfei.site.ai.agent.api.dto.AuditLogResponseDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 操作审计查询接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
public class AuditLogController {

    @Resource
    private IAuditService auditService;

    /**
     * 分页查询审计日志。
     */
    @GetMapping("/logs")
    public Response<AuditLogPageResponseDTO> queryLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {
        try {
            int safeLimit = limit == null ? 50 : limit;
            int safeOffset = offset == null ? 0 : offset;
            List<AuditLogEntity> logs = auditService.query(userId, connectionId, action, riskLevel,
                    safeLimit, safeOffset);
            long total = auditService.count(userId, connectionId, action, riskLevel);

            AuditLogPageResponseDTO page = new AuditLogPageResponseDTO();
            page.setList(logs.stream().map(AuditLogController::toDTO).collect(Collectors.toList()));
            page.setTotal(total);
            page.setLimit(safeLimit);
            page.setOffset(safeOffset);
            return Response.<AuditLogPageResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(page)
                    .build();
        } catch (Exception e) {
            log.error("查询审计日志失败", e);
            return Response.<AuditLogPageResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** 动作字典（筛选器） */
    @GetMapping("/actions")
    public Response<List<AuditActionInfoDTO>> queryActions() {
        List<AuditActionInfoDTO> actions = Arrays.stream(AuditActionEnum.values())
                .map(action -> new AuditActionInfoDTO(action.name(), action.getLabel(),
                        action.getDefaultRisk().name(), action.getDefaultRisk().getLabel()))
                .collect(Collectors.toList());
        return Response.<List<AuditActionInfoDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(actions)
                .build();
    }

    private static AuditLogResponseDTO toDTO(AuditLogEntity entity) {
        AuditLogResponseDTO dto = new AuditLogResponseDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setConnectionId(entity.getConnectionId());
        dto.setConnectionName(entity.getConnectionName());
        dto.setAction(entity.getAction() == null ? null : entity.getAction().name());
        dto.setActionLabel(entity.getAction() == null ? null : entity.getAction().getLabel());
        dto.setResource(entity.getResource());
        dto.setDetail(entity.getDetail());
        dto.setRiskLevel(entity.getRiskLevel() == null ? null : entity.getRiskLevel().name());
        dto.setRiskLabel(entity.getRiskLevel() == null ? null : entity.getRiskLevel().getLabel());
        dto.setSuccess(entity.getSuccess());
        dto.setDurationMs(entity.getDurationMs());
        dto.setClientIp(entity.getClientIp());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}

package jainingfei.site.ai.agent.domain.security.service.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.adapter.repository.IAuditLogRepository;
import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.RiskLevelEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 审计服务实现——写路径吞异常（审计绝不阻断业务），读路径同样降级为空结果。
 */
@Slf4j
@Service
public class AuditService implements IAuditService {

    /** resource / detail 落库上限，防止把整段命令输出写进审计表 */
    private static final int MAX_RESOURCE_LENGTH = 1000;
    private static final int MAX_DETAIL_LENGTH = 2000;

    @Resource
    private IAuditLogRepository auditLogRepository;

    @Resource
    private SecurityProperties securityProperties;

    @Override
    public void record(String userId, String connectionId, String connectionName,
                       AuditActionEnum action, String resource, String detail,
                       RiskLevelEnum riskLevel, boolean success, long durationMs, String clientIp) {
        if (!securityProperties.getAudit().isEnabled()) {
            return;
        }
        try {
            RiskLevelEnum risk = riskLevel != null
                    ? riskLevel
                    : (action != null ? action.getDefaultRisk() : RiskLevelEnum.LOW);
            auditLogRepository.save(AuditLogEntity.builder()
                    .userId(defaultUser(userId))
                    .connectionId(connectionId)
                    .connectionName(connectionName)
                    .action(action)
                    .resource(truncate(resource, MAX_RESOURCE_LENGTH))
                    .detail(truncate(detail, MAX_DETAIL_LENGTH))
                    .riskLevel(risk)
                    .success(success)
                    .durationMs(Math.max(durationMs, 0))
                    .clientIp(clientIp)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            // 审计表缺失 / 数据库抖动都不应影响主流程
            log.warn("审计记录写入失败 action={} resource={} error={}",
                    action, truncate(resource, 120), e.getMessage());
        }
    }

    @Override
    public List<AuditLogEntity> query(String userId, String connectionId, String action,
                                      String riskLevel, int limit, int offset) {
        try {
            int safeLimit = Math.min(Math.max(limit, 1), securityProperties.getAudit().getMaxQueryLimit());
            return auditLogRepository.query(userId, connectionId, action, riskLevel,
                    safeLimit, Math.max(offset, 0));
        } catch (Exception e) {
            log.warn("审计记录查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public long count(String userId, String connectionId, String action, String riskLevel) {
        try {
            return auditLogRepository.count(userId, connectionId, action, riskLevel);
        } catch (Exception e) {
            log.warn("审计记录计数失败: {}", e.getMessage());
            return 0L;
        }
    }

    private static String defaultUser(String userId) {
        return (userId == null || userId.isBlank()) ? "default" : userId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…[截断]";
    }
}

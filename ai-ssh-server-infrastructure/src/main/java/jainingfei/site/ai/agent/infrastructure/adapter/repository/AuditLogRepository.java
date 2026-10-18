package jainingfei.site.ai.agent.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.adapter.repository.IAuditLogRepository;
import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.RiskLevelEnum;
import jainingfei.site.ai.agent.infrastructure.dao.IAuditLogDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.AuditLogPO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 操作审计仓储实现。
 */
@Component
public class AuditLogRepository implements IAuditLogRepository {

    @Resource
    private IAuditLogDao auditLogDao;

    @Override
    public void save(AuditLogEntity entity) {
        auditLogDao.insert(toPO(entity));
    }

    @Override
    public List<AuditLogEntity> query(String userId, String connectionId, String action,
                                      String riskLevel, int limit, int offset) {
        LambdaQueryWrapper<AuditLogPO> wrapper = buildWrapper(userId, connectionId, action, riskLevel)
                .orderByDesc(AuditLogPO::getCreatedAt)
                .orderByDesc(AuditLogPO::getId)
                .last("LIMIT " + Math.max(limit, 1) + " OFFSET " + Math.max(offset, 0));
        List<AuditLogPO> poList = auditLogDao.selectList(wrapper);
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public long count(String userId, String connectionId, String action, String riskLevel) {
        Long total = auditLogDao.selectCount(buildWrapper(userId, connectionId, action, riskLevel));
        return total == null ? 0L : total;
    }

    private LambdaQueryWrapper<AuditLogPO> buildWrapper(String userId, String connectionId,
                                                        String action, String riskLevel) {
        LambdaQueryWrapper<AuditLogPO> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(userId)) {
            wrapper.eq(AuditLogPO::getUserId, userId);
        }
        if (StringUtils.hasText(connectionId)) {
            wrapper.eq(AuditLogPO::getConnectionId, connectionId);
        }
        if (StringUtils.hasText(action)) {
            wrapper.eq(AuditLogPO::getAction, action.trim().toUpperCase());
        }
        if (StringUtils.hasText(riskLevel)) {
            wrapper.eq(AuditLogPO::getRiskLevel, riskLevel.trim().toUpperCase());
        }
        return wrapper;
    }

    private AuditLogPO toPO(AuditLogEntity entity) {
        AuditLogPO po = new AuditLogPO();
        po.setUserId(entity.getUserId());
        po.setConnectionId(entity.getConnectionId());
        po.setConnectionName(entity.getConnectionName());
        po.setAction(entity.getAction() != null ? entity.getAction().name() : null);
        po.setResource(entity.getResource());
        po.setDetail(entity.getDetail());
        po.setRiskLevel(entity.getRiskLevel() != null ? entity.getRiskLevel().name() : null);
        po.setSuccess(Boolean.TRUE.equals(entity.getSuccess()) ? 1 : 0);
        po.setDurationMs(entity.getDurationMs());
        po.setClientIp(entity.getClientIp());
        po.setCreatedAt(entity.getCreatedAt());
        return po;
    }

    private AuditLogEntity toEntity(AuditLogPO po) {
        return AuditLogEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .connectionId(po.getConnectionId())
                .connectionName(po.getConnectionName())
                .action(parseAction(po.getAction()))
                .resource(po.getResource())
                .detail(po.getDetail())
                .riskLevel(RiskLevelEnum.fromName(po.getRiskLevel()))
                .success(po.getSuccess() != null && po.getSuccess() == 1)
                .durationMs(po.getDurationMs())
                .clientIp(po.getClientIp())
                .createdAt(po.getCreatedAt())
                .build();
    }

    private AuditActionEnum parseAction(String name) {
        if (name == null) {
            return null;
        }
        try {
            return AuditActionEnum.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

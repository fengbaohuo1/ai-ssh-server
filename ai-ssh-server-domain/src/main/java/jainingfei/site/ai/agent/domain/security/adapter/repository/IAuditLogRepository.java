package jainingfei.site.ai.agent.domain.security.adapter.repository;

import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;

import java.util.List;

/**
 * 操作审计仓储接口。
 */
public interface IAuditLogRepository {

    /** 追加一条审计记录 */
    void save(AuditLogEntity entity);

    /**
     * 分页查询审计记录（按时间倒序）。
     *
     * @param userId       用户 ID，可空
     * @param connectionId 连接 ID，可空
     * @param action       动作名，可空
     * @param riskLevel    风险等级名，可空
     * @param limit        条数
     * @param offset       偏移
     */
    List<AuditLogEntity> query(String userId, String connectionId, String action,
                               String riskLevel, int limit, int offset);

    /** 与 query 相同过滤条件下的总数 */
    long count(String userId, String connectionId, String action, String riskLevel);
}

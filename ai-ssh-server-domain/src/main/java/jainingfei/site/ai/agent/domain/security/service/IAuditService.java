package jainingfei.site.ai.agent.domain.security.service;

import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.AuditActionEnum;
import jainingfei.site.ai.agent.domain.security.model.valobj.enums.RiskLevelEnum;

import java.util.List;

/**
 * 操作审计服务——所有写操作与高危读取的统一落点。
 *
 * <p>设计约束：
 * <ul>
 *   <li>审计失败<b>不能</b>影响业务主流程（例如连接被删除后审计表短暂不可用时，删除仍应成功）</li>
 *   <li>记录入口只暴露"语义参数"，风险等级默认取动作声明，允许调用方按实际情况覆盖</li>
 *   <li>敏感内容（密码、私钥）永远不进入 resource / detail</li>
 * </ul>
 */
public interface IAuditService {

    /**
     * 记录一次操作。
     *
     * @param userId         用户
     * @param connectionId   连接 ID（可空）
     * @param connectionName 连接名（可空）
     * @param action         动作
     * @param resource       资源（命令 / 路径 / 会话）
     * @param detail         明细（可空，服务端统一截断）
     * @param riskLevel      风险等级（null = 取动作默认）
     * @param success        是否成功
     * @param durationMs     耗时（毫秒）
     * @param clientIp       客户端 IP（可空）
     */
    void record(String userId, String connectionId, String connectionName,
                AuditActionEnum action, String resource, String detail,
                RiskLevelEnum riskLevel, boolean success, long durationMs, String clientIp);

    /** 简化入口（风险等级取动作默认） */
    default void record(String userId, String connectionId, String connectionName,
                        AuditActionEnum action, String resource, boolean success) {
        record(userId, connectionId, connectionName, action, resource, null,
                null, success, 0L, null);
    }

    /** 分页查询 */
    List<AuditLogEntity> query(String userId, String connectionId, String action,
                               String riskLevel, int limit, int offset);

    /** 计数 */
    long count(String userId, String connectionId, String action, String riskLevel);
}

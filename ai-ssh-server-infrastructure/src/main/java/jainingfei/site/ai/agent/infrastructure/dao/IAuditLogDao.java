package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.AuditLogPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计 DAO
 */
@Mapper
public interface IAuditLogDao extends BaseMapper<AuditLogPO> {
}

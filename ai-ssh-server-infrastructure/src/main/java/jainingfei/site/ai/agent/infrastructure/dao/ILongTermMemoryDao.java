package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.LongTermMemoryPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 长期记忆 DAO
 */
@Mapper
public interface ILongTermMemoryDao extends BaseMapper<LongTermMemoryPO> {
}

package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMilestonePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话里程碑 DAO
 */
@Mapper
public interface IChatMilestoneDao extends BaseMapper<ChatMilestonePO> {
}

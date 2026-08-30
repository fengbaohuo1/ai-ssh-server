package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话会话 DAO
 */
@Mapper
public interface IChatSessionDao extends BaseMapper<ChatSessionPO> {
}

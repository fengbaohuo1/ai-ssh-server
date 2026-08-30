package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 DAO
 */
@Mapper
public interface IChatMessageDao extends BaseMapper<ChatMessagePO> {
}

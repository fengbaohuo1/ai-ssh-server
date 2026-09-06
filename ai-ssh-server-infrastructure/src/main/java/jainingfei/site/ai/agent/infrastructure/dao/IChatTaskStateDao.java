package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatTaskStatePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话任务态 DAO（chat_task_state）
 */
@Mapper
public interface IChatTaskStateDao extends BaseMapper<ChatTaskStatePO> {
}

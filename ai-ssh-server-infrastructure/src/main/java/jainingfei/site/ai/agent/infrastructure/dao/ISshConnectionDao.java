package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.SshConnectionPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSH 连接 DAO
 */
@Mapper
public interface ISshConnectionDao extends BaseMapper<SshConnectionPO> {
}

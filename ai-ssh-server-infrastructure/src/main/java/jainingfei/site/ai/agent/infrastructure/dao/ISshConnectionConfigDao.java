package jainingfei.site.ai.agent.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jainingfei.site.ai.agent.infrastructure.dao.po.SshConnectionConfigPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSH 连接高级配置 DAO
 */
@Mapper
public interface ISshConnectionConfigDao extends BaseMapper<SshConnectionConfigPO> {
}

package jainingfei.site.ai.agent.domain.agent.adapter.repository;

import jainingfei.site.ai.agent.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆仓储接口——long_term_memory 表的领域出口。
 *
 * <p>去重策略：唯一键 (user_id, memory_type, memory_key) 兜底，
 * saveOrUpdate 先查后写——已存在则更新内容并累加 hit_count，不存在则插入。
 * 不用 INSERT ON DUPLICATE KEY UPDATE：hit_count 需要 +1 且更新字段多，
 * 写入频率低，先查后写可读性更好。
 */
public interface ILongTermMemoryRepository {

    /**
     * 按唯一键去重保存/更新一条记忆
     */
    void saveOrUpdate(LongTermMemoryEntity memoryEntity);

    /**
     * 查询用户最近的记忆列表（粗筛候选集，按更新时间倒序）
     */
    List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit);
}

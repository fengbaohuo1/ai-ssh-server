package jainingfei.site.ai.agent.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.ILongTermMemoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.LongTermMemoryEntity;
import jainingfei.site.ai.agent.infrastructure.dao.ILongTermMemoryDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.LongTermMemoryPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆仓储实现——long_term_memory 的持久化出口。
 *
 * <p>saveOrUpdate 用"先查后写"而非 INSERT ON DUPLICATE KEY UPDATE：
 * 已存在时 hit_count 需要 +1 且更新多个字段，写入频率低，可读性优先。
 */
@Slf4j
@Component
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    @Override
    public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        LongTermMemoryPO existPO = longTermMemoryDao.selectOne(
                new LambdaQueryWrapper<LongTermMemoryPO>()
                        .eq(LongTermMemoryPO::getUserId, memoryEntity.getUserId())
                        .eq(LongTermMemoryPO::getMemoryType, memoryEntity.getMemoryType())
                        .eq(LongTermMemoryPO::getMemoryKey, memoryEntity.getMemoryKey())
                        .last("LIMIT 1")
        );

        if (existPO == null) {
            longTermMemoryDao.insert(toPO(memoryEntity));
            return;
        }

        existPO.setSessionId(memoryEntity.getSessionId());
        existPO.setContent(memoryEntity.getContent());
        existPO.setKeywords(memoryEntity.getKeywords());
        existPO.setSourceRole(memoryEntity.getSourceRole());
        existPO.setConfidence(memoryEntity.getConfidence() != null
                ? BigDecimal.valueOf(memoryEntity.getConfidence()) : null);
        existPO.setHitCount((existPO.getHitCount() == null ? 0 : existPO.getHitCount()) + 1);
        longTermMemoryDao.updateById(existPO);
    }

    @Override
    public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
        List<LongTermMemoryPO> poList = longTermMemoryDao.selectList(
                new LambdaQueryWrapper<LongTermMemoryPO>()
                        .eq(LongTermMemoryPO::getUserId, userId)
                        .orderByDesc(LongTermMemoryPO::getUpdatedAt)
                        .orderByDesc(LongTermMemoryPO::getId)
                        .last("LIMIT " + Math.max(limit, 1))
        );
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    // ==================== 转换方法 ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        LongTermMemoryPO po = new LongTermMemoryPO();
        po.setUserId(entity.getUserId());
        po.setSessionId(entity.getSessionId());
        po.setMemoryType(entity.getMemoryType());
        po.setMemoryKey(entity.getMemoryKey());
        po.setContent(entity.getContent());
        po.setKeywords(entity.getKeywords());
        po.setSourceRole(entity.getSourceRole());
        po.setConfidence(entity.getConfidence() != null
                ? BigDecimal.valueOf(entity.getConfidence()) : BigDecimal.valueOf(0.50));
        po.setHitCount(entity.getHitCount() != null ? entity.getHitCount() : 1);
        return po;
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence() != null ? po.getConfidence().doubleValue() : null)
                .hitCount(po.getHitCount())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}

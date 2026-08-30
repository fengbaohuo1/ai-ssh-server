package jainingfei.site.ai.agent.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 长期记忆实体 — long_term_memory 表的领域模型
 *
 * <p>memoryKey 是去重键：同一用户同类型下语义相同的记忆共用一个 key，
 * 重复出现走更新（hit_count+1）而非新增——去重由表唯一键兜底。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryEntity {

    private Long id;

    /** 用户 ID */
    private String userId;

    /** 来源会话 ID */
    private String sessionId;

    /** 记忆类型: USER_PREFERENCE/ENVIRONMENT_FACT/SOFTWARE_FACT/TROUBLESHOOTING_CASE */
    private String memoryType;

    /** 记忆去重键 */
    private String memoryKey;

    /** 记忆内容 */
    private String content;

    /** 关键词集合 (逗号分隔) */
    private String keywords;

    /** 来源角色: user/tool/assistant */
    private String sourceRole;

    /** 置信度 */
    private Double confidence;

    /** 命中/更新次数 */
    private Integer hitCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆持久化对象 — 对应表 long_term_memory
 *
 * <p>去重骨架是唯一键 (user_id, memory_type, memory_key)：
 * 同一用户同类型的同一条记忆只存一行，重复出现走 update 并累加 hit_count。
 */
@Data
@TableName("long_term_memory")
public class LongTermMemoryPO {

    @TableId(type = IdType.AUTO)
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
    private BigDecimal confidence;

    /** 命中/更新次数 */
    private Integer hitCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

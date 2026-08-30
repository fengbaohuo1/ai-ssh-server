package jainingfei.site.ai.agent.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话会话实体 — chat_session 表的领域模型
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionEntity {

    /** 会话 ID (UUID)，与 ADK Session id 一致 */
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 消息数量 */
    private Integer messageCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

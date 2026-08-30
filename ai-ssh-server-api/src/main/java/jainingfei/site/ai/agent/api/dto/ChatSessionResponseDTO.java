package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * 会话列表响应 DTO——对应 chat_session 表（2-8 会话历史）
 */
@Data
public class ChatSessionResponseDTO {

    /** 会话 ID */
    private String sessionId;

    /** 智能体 ID */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 消息数量 */
    private Integer messageCount;

    /** 更新时间（yyyy-MM-dd HH:mm:ss） */
    private String updatedAt;
}

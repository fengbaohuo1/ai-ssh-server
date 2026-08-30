package jainingfei.site.ai.agent.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息实体 — chat_message 表的领域模型
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageEntity {

    private Long id;

    /** 会话 ID */
    private String sessionId;

    /** 角色: user/assistant/tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（role=tool 时） */
    private String toolName;

    /** 工具调用 ID（role=tool 时） */
    private String toolCallId;

    /** 优先级: CRITICAL/HIGH/MEDIUM/LOW */
    private String priority;

    /** 预估 token 数（约 content 长度 / 2） */
    private Integer tokenCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

}

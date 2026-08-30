package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * 会话消息响应 DTO——对应 chat_message 表（2-8 会话历史回放）
 */
@Data
public class ChatMessageResponseDTO {

    /** 角色: user/assistant/tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（role=tool 时） */
    private String toolName;

    /** 工具调用 ID（role=tool 时） */
    private String toolCallId;

    /** 创建时间（yyyy-MM-dd HH:mm:ss） */
    private String createdAt;
}

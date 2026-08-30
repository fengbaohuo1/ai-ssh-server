package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息持久化对象 — 对应表 chat_message
 */
@Data
@TableName("chat_message")
public class ChatMessagePO {

    @TableId(type = IdType.AUTO)
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

    /** 预估 token 数 */
    private Integer tokenCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

}

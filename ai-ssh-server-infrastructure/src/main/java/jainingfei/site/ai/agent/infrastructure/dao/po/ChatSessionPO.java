package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话持久化对象 — 对应表 chat_session
 */
@Data
@TableName("chat_session")
public class ChatSessionPO {

    /** 会话 ID (UUID)，与 ADK Session id 一致 */
    @TableId(type = IdType.INPUT)
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

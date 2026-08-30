package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话里程碑持久化对象 — 对应表 chat_milestone
 */
@Data
@TableName("chat_milestone")
public class ChatMilestonePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID */
    private String sessionId;

    /** 类型: TASK_CHANGE/USER_CORRECTION/ERROR 等 */
    private String type;

    /** 内容摘要 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createdAt;

}

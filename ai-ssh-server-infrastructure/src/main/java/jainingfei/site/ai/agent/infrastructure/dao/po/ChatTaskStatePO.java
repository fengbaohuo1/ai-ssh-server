package jainingfei.site.ai.agent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话任务态持久化对象 — 对应表 chat_task_state（chat_* 会话族）
 * <p>随会话一行存储：任务锚点/根意图/步骤留痕/完成与失败标记，服务重启后冷启动恢复。
 */
@Data
@TableName("chat_task_state")
public class ChatTaskStatePO {

    /** 会话 ID，与 chat_session.id 一致 */
    @TableId(type = IdType.INPUT)
    private String sessionId;

    /** 根意图枚举名（IntentTypeEnumVO.name） */
    private String rootIntent;

    /** 任务锚点描述 */
    private String taskDescription;

    /** 步骤留痕：行分隔（分隔符 \u001F，避免与命令内容冲突） */
    private String steps;

    /** 当前步骤下标 */
    private Integer currentStepIndex;

    /** 任务是否完成归档（0/1） */
    private Integer completed;

    /** 最近一次反馈是否失败/回合是否中断（0/1） */
    private Integer lastFeedbackFailed;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}

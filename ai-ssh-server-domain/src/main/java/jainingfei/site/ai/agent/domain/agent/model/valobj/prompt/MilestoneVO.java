package jainingfei.site.ai.agent.domain.agent.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 里程碑事件——记录对话中的关键节点（用户纠偏、任务切换、工具报错等）。
 *
 * <p>由 {@code MilestoneTracker} 基于正则自动识别，供 {@code DynamicPromptBuilder}
 * 注入到每轮用户消息的动态段中，让 LLM 知道"发生过什么"。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MilestoneVO {

    /** 事件类型 */
    private Type type;

    /** 事件内容摘要（截断到 200 字符） */
    private String content;

    /** 发生时间戳（毫秒） */
    private long timestamp;

    public enum Type {
        /** 任务切换：用户说"不对""换个思路" */
        TASK_CHANGE,
        /** 任务完成：用户说"完成了""搞定" */
        TASK_COMPLETE,
        /** 用户纠偏：用户说"不要""停""别" */
        USER_CORRECTION,
        /** 工具报错：命令输出含 error/failed/exception 等关键词 */
        ERROR,
        /** 决策记录：预留 */
        DECISION,
        /** 文件切换：预留 */
        FILE_SWITCH
    }
}

package jainingfei.site.ai.agent.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配置写入/重装结果。
 *
 * <p>v3.1 起配置文件保存后会<b>立即热重装</b>智能体（无需重启进程）：
 * <ul>
 *   <li>{@link #restartRequired}：保留旧字段兼容老前端，现在恒为 {@code false}；</li>
 *   <li>{@link #applied}：本次保存是否已成功热重装生效；{@code false} 表示文件已保存但装配失败，
 *       当前继续使用旧装配；</li>
 *   <li>{@link #applyMessage}：可直接展示给用户的说明（成功或失败原因）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class AgentConfigWriteResultDTO {

    /** 是否需要重启后生效（兼容字段；热重装上线后恒为 false） */
    private boolean restartRequired;

    /** 是否已热重装生效 */
    private boolean applied;

    /** 生效/失败说明（前端直接展示；失败时含原因） */
    private String applyMessage;
}

package jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 动态派发任务计划——一批任务 + 调度策略（教程 2-10）。
 *
 * <p>两个工具入口（主智能体自拆的 dispatchSubAgents、规划器生成的 planAndDispatchSubAgents）
 * 最终都收敛成这一个对象，交给同一个 {@code DynamicAgentOrchestrator} 执行。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTaskPlan {

    /** 任务列表（含依赖关系，构成 DAG） */
    private List<DynamicTask> tasks;

    /** 并发上限：同一时刻最多几个子智能体在跑（默认 4，编排器钳制到 [1,8]） */
    @Builder.Default
    private Integer maxConcurrency = 4;

    /** 是否需要人工确认（预留字段，当前未启用） */
    @Builder.Default
    private Boolean requireConfirmation = false;

    /**
     * 失败即中止策略（默认 false）：
     * <ul>
     *   <li>false —— 组合投资：某个任务失败不影响无依赖分支，损失局部化（适合批量诊断）</li>
     *   <li>true  —— 多米诺止损：首个任务失败后不再调度新任务（进行中的跑完），
     *       剩余 PENDING 全部 SKIPPED（适合"前置检查失败就不该继续安装"的变更链路）</li>
     * </ul>
     */
    @Builder.Default
    private Boolean failFast = false;

}

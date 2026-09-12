package jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态派发任务——编排器与执行器之间传递的"一个子任务"（教程 2-10）。
 *
 * <p>任务对象同时也是<b>执行结果载体</b>：编排前只有描述信息，执行过程中原地回写
 * 状态/结果/尝试次数，最终作为 Map 的一部分回灌给父智能体，模型据此判断下一步。
 *
 * <p>重试语义（安全阀）：
 * <ul>
 *   <li>仅对"执行期失败"（超时/异常）生效；子智能体不存在这类确定性错误直接 FAILED，不重试</li>
 *   <li>{@code maxRetries} 默认 0，校验层钳制上限 3；每次重试用独立会话（带 -attempt 后缀），
 *       失败的执行不污染重试</li>
 *   <li><b>子智能体具备 SSH 写能力：非幂等的变更任务必须保持 0</b>——
 *       "df -h 失败后重试"与"apt install 装到一半失败后重试"风险完全不同，
 *       是否需要重试交给规划器/父智能体决定，默认不重试</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTask {

    /** 任务唯一 ID（LLM 未给时由派发工具补 task-&lt;uuid&gt;） */
    private String taskId;

    /** 派发给哪个子智能体（必须是已装配且在白名单内的名字） */
    private String agentName;

    /** 任务描述（下发给子智能体的完整指令） */
    private String request;

    /** 依赖的任务 ID（DAG 边）：全部 COMPLETED 后本任务才可调度 */
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();

    /** 单任务超时（秒） */
    @Builder.Default
    private Integer timeoutSeconds = 120;

    /** 失败后自动重试次数（不含首次）；非幂等写任务必须为 0 */
    @Builder.Default
    private Integer maxRetries = 0;

    /** 实际执行尝试次数（回写给父智能体观察重试轨迹） */
    @Builder.Default
    private Integer attempts = 0;

    /** 任务状态 */
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /** 执行结果（子智能体最终回复） */
    @Builder.Default
    private String result = "";

    /** 失败原因 / 跳过原因 */
    @Builder.Default
    private String error = "";

}

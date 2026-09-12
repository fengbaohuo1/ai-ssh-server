package jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic;

import lombok.Builder;
import lombok.Value;

/**
 * 子智能体派发上下文——一次派发（或一批派发）需要的环境信息，显式携带而不是依赖线程状态（教程 2-10）。
 *
 * <p>为什么需要它：子智能体跑在<b>独立会话、独立线程</b>上（批量派发还走线程池），
 * "这次派发属于哪个配置表、哪个父智能体、绑定了哪条 SSH 连接"这些信息无法从子会话推断出来，
 * 必须显式打包传递。
 *
 * <pre>
 *   派发工具（从 ToolContext 提取）→ AgentExecutionContext → 编排器 → 执行器
 *        ↓                                             ↓
 *   子会话初始 state 写入 terminalSessionId      子智能体工具从 state 读取，在正确连接上执行命令
 * </pre>
 *
 * <p>与教程的差异：教程用 {@code SshExecuteAdkTool} 的 {@code ThreadLocal} 传终端会话，
 * 而 ADK 的工具执行线程由 {@code Schedulers.io()} 决定、批量路径又在线程池里，
 * ThreadLocal 可见性没有保证。我们用 {@code appName + agentName} 精确定位、用
 * <b>子会话初始 state</b> 透传终端会话，全程与线程解耦。
 */
@Value
@Builder
public class AgentExecutionContext {

    /** 发起对话的用户 ID */
    String userId;

    /** 配置表 app-name（ADK App 名，用于在 AgentCatalog 中精确定位子智能体分组） */
    String appName;

    /** 发起派发的父智能体名（ADK agentName） */
    String agentName;

    /** 父会话的 invocationId，仅用于日志追溯 */
    String parentSessionId;

    /** 父会话绑定的 SSH 终端会话 ID（connectionId），派发时透传给子会话初始 state */
    String terminalSessionId;

    /** 当前执行的任务 ID（预留） */
    String taskId;

}

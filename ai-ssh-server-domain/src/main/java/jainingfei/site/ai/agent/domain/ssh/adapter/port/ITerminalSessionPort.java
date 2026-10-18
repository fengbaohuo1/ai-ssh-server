package jainingfei.site.ai.agent.domain.ssh.adapter.port;

import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;

/**
 * 终端会话端口接口 — 定义基础设施层必须实现的终端操作。
 * <p>
 * 遵循 DDD 端口适配器模式：领域层定义接口，基础设施层实现。
 * 一个连接只有一个终端会话，AI 和用户共用。
 */
public interface ITerminalSessionPort {

    /**
     * 执行命令并返回输出（终端会话不存在时自动创建）。
     * ChannelShell 方式，命令之间有状态延续（如 cd 后 ls 可见）。
     *
     * @param connectionId 已连接的 SSH 连接 ID
     * @param command      要执行的 Shell 命令
     * @return 命令输出文本
     */
    String executeCommand(String connectionId, String command);

    /**
     * 独立通道执行命令——ChannelExec，每次新通道，无回显/ANSI/prompt/状态污染。
     * AI 工具专用。
     *
     * @param connectionId SSH 连接 ID
     * @param command      Shell 命令
     * @return 干净的 stdout
     */
    String execCommand(String connectionId, String command);

    /**
     * 独立通道执行命令，返回结构化结果（stdout / stderr / 退出码 / 超时 / 截断）。
     *
     * <p>与 {@link #execCommand} 走同一条 ChannelExec 路径，区别只在于把 stderr 与退出码
     * 一起带回领域层——AI 工具据此判断真实成功与否，而不是靠 stdout 文本猜。
     *
     * @param connectionId SSH 连接 ID
     * @param command      Shell 命令
     * @return 结构化执行结果
     */
    SshCommandResult execCommandDetailed(String connectionId, String command);

    /**
     * 检查终端会话是否存在且活跃
     *
     * @param connectionId SSH 连接 ID
     * @return true 如果会话存在
     */
    boolean sessionExists(String connectionId);

    /**
     * 关闭终端会话
     *
     * @param connectionId SSH 连接 ID
     */
    void closeSession(String connectionId);
}

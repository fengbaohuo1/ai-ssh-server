package jainingfei.site.ai.agent.domain.ssh.service;

import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;

/**
 * SSH 终端领域服务接口。
 * <p>
 * 提供基于 {@code connectionId} 的命令执行能力。
 * 底层由 {@code UnifiedSessionManager} 统一管理 ChannelShell 生命周期，
 * 确保一个连接只有一个终端会话，人机和 AI 共用。
 */
public interface ISshTerminalService {

    /**
     * 在指定连接的终端中执行命令并返回输出。
     * ChannelShell 方式，命令之间有状态延续。
     *
     * @param connectionId 已连接的 SSH 连接 ID
     * @param command      要执行的 Shell 命令
     * @return 命令输出文本
     */
    String executeCommand(String connectionId, String command);

    /**
     * 独立通道执行命令——ChannelExec，每次新通道，返回纯净 stdout。
     * AI 工具专用，无回显/ANSI/prompt/状态污染。
     */
    String execCommand(String connectionId, String command);

    /**
     * 独立通道执行命令，返回结构化结果——stdout / stderr / 退出码 / 超时 / 截断。
     *
     * <p>AI 工具应优先走这个入口：只有退出码能可靠区分"命令成功但无输出"与"命令失败"。
     */
    SshCommandResult execCommandDetailed(String connectionId, String command);

    /**
     * 检查终端会话是否存在且活跃
     *
     * @param connectionId SSH 连接 ID
     * @return true 如果终端会话存在
     */
    boolean sessionExists(String connectionId);

    /**
     * 关闭终端会话
     *
     * @param connectionId SSH 连接 ID
     */
    void closeTerminal(String connectionId);
}

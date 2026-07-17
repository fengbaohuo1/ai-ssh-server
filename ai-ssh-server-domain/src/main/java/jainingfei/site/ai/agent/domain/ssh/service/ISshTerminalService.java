package jainingfei.site.ai.agent.domain.ssh.service;

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
     * 终端会话不存在时会自动创建。
     *
     * @param connectionId 已连接的 SSH 连接 ID
     * @param command      要执行的 Shell 命令
     * @return 命令输出文本
     */
    String executeCommand(String connectionId, String command);

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

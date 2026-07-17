package jainingfei.site.ai.agent.domain.ssh.adapter.port;

/**
 * 终端会话端口接口 — 定义基础设施层必须实现的终端操作。
 * <p>
 * 遵循 DDD 端口适配器模式：领域层定义接口，基础设施层实现。
 * 一个连接只有一个终端会话，AI 和用户共用。
 */
public interface ITerminalSessionPort {

    /**
     * 执行命令并返回输出（终端会话不存在时自动创建）
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

package jainingfei.site.ai.agent.domain.ssh.adapter.port;

/**
 * 终端会话端口接口 — 定义基础设施层必须实现的终端（ChannelShell）操作。
 * <p>
 * 遵循 DDD 端口适配器模式：领域层定义接口，基础设施层实现。
 * 负责管理 JSch ChannelShell 的生命周期（打开、读写、调整大小、关闭）。
 */
public interface ITerminalSessionPort {

    /**
     * 打开终端会话
     *
     * @param connectionId 已建立的 SSH 连接 ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return 终端会话唯一标识
     */
    String openTerminal(String connectionId, int cols, int rows);

    /**
     * 向终端写入命令（追加换行符）
     *
     * @param sessionId 终端会话 ID
     * @param command   要执行的命令
     */
    void write(String sessionId, String command);

    /**
     * 从终端读取当前输出（非阻塞，返回缓冲区内容并清空）
     *
     * @param sessionId 终端会话 ID
     * @return 终端输出文本
     */
    String read(String sessionId);

    /**
     * 调整终端窗口大小
     *
     * @param sessionId 终端会话 ID
     * @param cols      新的列数
     * @param rows      新的行数
     */
    void resize(String sessionId, int cols, int rows);

    /**
     * 关闭终端会话
     *
     * @param sessionId 终端会话 ID
     */
    void closeSession(String sessionId);
}

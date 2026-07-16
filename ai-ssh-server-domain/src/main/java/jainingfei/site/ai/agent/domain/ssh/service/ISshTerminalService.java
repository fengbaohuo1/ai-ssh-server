package jainingfei.site.ai.agent.domain.ssh.service;

import jainingfei.site.ai.agent.domain.ssh.model.entity.TerminalSessionEntity;

/**
 * SSH 终端领域服务接口。
 * <p>
 * 提供终端会话的生命周期管理及命令执行能力。
 * 领域层编排 {@code ITerminalSessionPort}（基础设施）实现具体操作。
 */
public interface ISshTerminalService {

    /**
     * 打开终端会话
     *
     * @param connectionId 已连接的 SSH 连接 ID
     * @param cols         终端列数
     * @param rows         终端行数
     * @return 终端会话实体
     */
    TerminalSessionEntity openTerminal(String connectionId, int cols, int rows);

    /**
     * 执行命令并返回输出
     *
     * @param sessionId 终端会话 ID
     * @param command   要执行的 Shell 命令
     * @return 命令输出文本
     */
    String executeCommand(String sessionId, String command);

    /**
     * 关闭终端会话
     *
     * @param sessionId 终端会话 ID
     */
    void closeTerminal(String sessionId);

    /**
     * 检查终端会话是否存在且活跃
     *
     * @param sessionId 终端会话 ID
     * @return true 如果会话存在且活跃
     */
    boolean sessionExists(String sessionId);
}

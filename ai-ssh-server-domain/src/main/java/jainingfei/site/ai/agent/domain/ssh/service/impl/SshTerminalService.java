package jainingfei.site.ai.agent.domain.ssh.service.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ITerminalSessionPort;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SSH 终端领域服务实现。
 * <p>
 * 通过 {@link ITerminalSessionPort} 端口接口委托基础设施层操作，
 * 底层由 {@code UnifiedSessionManager} 统一管理 ChannelShell 生命周期。
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    @Resource
    private ITerminalSessionPort terminalSessionPort;

    @Override
    public String executeCommand(String connectionId, String command) {
        log.debug("执行命令 connectionId={} command={}", connectionId, command);
        return terminalSessionPort.executeCommand(connectionId, command);
    }

    @Override
    public String execCommand(String connectionId, String command) {
        log.debug("AI 执行命令 connectionId={} command={}", connectionId, command);
        return terminalSessionPort.execCommand(connectionId, command);
    }

    @Override
    public boolean sessionExists(String connectionId) {
        return terminalSessionPort.sessionExists(connectionId);
    }

    @Override
    public void closeTerminal(String connectionId) {
        log.info("关闭终端会话 connectionId={}", connectionId);
        terminalSessionPort.closeSession(connectionId);
    }
}

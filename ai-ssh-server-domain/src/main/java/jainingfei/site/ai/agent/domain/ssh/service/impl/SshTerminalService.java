package jainingfei.site.ai.agent.domain.ssh.service.impl;

import jainingfei.site.ai.agent.domain.ssh.adapter.port.ITerminalSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.model.entity.TerminalSessionEntity;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 终端领域服务实现。
 * <p>
 * 编排 {@link ISshSessionPort} 和 {@link ITerminalSessionPort} 完成终端生命周期管理。
 * 领域层维护 {@link TerminalSessionEntity} 缓存，基础设施层维护 {@code ChannelShell}。
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    private final ISshSessionPort sshSessionPort;
    private final ITerminalSessionPort terminalSessionPort;

    /** sessionId → 终端会话实体 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionPort,
                              ITerminalSessionPort terminalSessionPort) {
        this.sshSessionPort = sshSessionPort;
        this.terminalSessionPort = terminalSessionPort;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 检查 SSH 连接是否已建立
        if (!sshSessionPort.isConnected(connectionId)) {
            throw new IllegalStateException("SSH 连接未建立，请先连接");
        }

        // 2. 清理同一 connectionId 的旧会话
        sessionCache.entrySet().removeIf(entry -> {
            if (connectionId.equals(entry.getValue().getConnectionId())) {
                log.info("清理旧终端会话 sessionId={} connectionId={}", entry.getKey(), connectionId);
                terminalSessionPort.closeSession(entry.getKey());
                return true;
            }
            return false;
        });

        // 3. 在基础设施层打开终端会话（ChannelShell）
        String sessionId = terminalSessionPort.openTerminal(connectionId, cols, rows);

        // 4. 创建并缓存领域实体
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(sessionId, entity);
        log.info("终端会话创建成功 sessionId={}", sessionId);
        return entity;
    }

    @Override
    public String executeCommand(String sessionId, String command) {
        log.debug("执行命令 sessionId={} command={}", sessionId, command);

        // 1. 校验会话
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        // 2. 写入命令
        terminalSessionPort.write(sessionId, command);

        // 3. 更新活跃时间
        entity.touch();

        // 4. 读取输出
        String output = terminalSessionPort.read(sessionId);
        log.debug("命令执行完成 sessionId={} outputLength={}", sessionId, output.length());
        return output;
    }

    @Override
    public void closeTerminal(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionPort.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        return entity != null && entity.isActive();
    }
}

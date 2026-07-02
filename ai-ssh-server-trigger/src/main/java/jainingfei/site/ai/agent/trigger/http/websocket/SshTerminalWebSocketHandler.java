package jainingfei.site.ai.agent.trigger.http.websocket;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.infrastructure.adapter.port.SshSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class SshTerminalWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private SshSessionPort sshSessionPort;

    private final ConcurrentHashMap<String, ChannelShell> channelMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "terminal-io");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        // 1. 从路径中提取 connectionId: /ws/terminal/{connectionId}
        String path = wsSession.getUri().getPath();
        String connectionId = path.substring(path.lastIndexOf('/') + 1);

        log.info("WebSocket terminal connected connectionId={} sessionId={}", connectionId, wsSession.getId());

        // 2. 获取 JSch Session
        Session jschSession = sshSessionPort.getSession(connectionId);
        if (jschSession == null || !jschSession.isConnected()) {
            log.error("SSH会话不存在或已断开 connectionId={}", connectionId);
            wsSession.sendMessage(new TextMessage("[31mSSH会话不存在或已断开，请先连接服务器[0m\r\n"));
            wsSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        try {
            // 3. 打开 ChannelShell
            ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setAgentForwarding(false);
            channel.connect(3000);

            channelMap.put(wsSession.getId(), channel);

            // 4. 启动线程：ChannelShell 输出流 → WebSocket
            InputStream inputStream = channel.getInputStream();
            executor.submit(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = inputStream.read(buffer)) != -1) {
                        if (wsSession.isOpen()) {
                            wsSession.sendMessage(new TextMessage(new String(buffer, 0, len, "UTF-8")));
                        }
                    }
                } catch (Exception e) {
                    log.warn("终端IO读取结束 sessionId={}", wsSession.getId());
                } finally {
                    cleanup(wsSession.getId());
                }
            });

        } catch (Exception e) {
            log.error("打开终端通道失败 connectionId={}", connectionId, e);
            wsSession.sendMessage(new TextMessage("[31m打开终端失败: " + e.getMessage() + "[0m\r\n"));
            wsSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        ChannelShell channel = channelMap.get(wsSession.getId());
        if (channel != null) {
            OutputStream out = channel.getOutputStream();
            out.write(message.getPayload().getBytes());
            out.flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        log.info("WebSocket terminal disconnected sessionId={}", wsSession.getId());
        cleanup(wsSession.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("WebSocket传输错误 sessionId={}", wsSession.getId(), exception);
        cleanup(wsSession.getId());
    }

    private void cleanup(String sessionId) {
        ChannelShell channel = channelMap.remove(sessionId);
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
    }
}

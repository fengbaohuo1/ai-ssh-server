package jainingfei.site.ai.agent.trigger.http.websocket;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.infrastructure.adapter.session.UnifiedSessionManager;
import jainingfei.site.ai.agent.infrastructure.adapter.session.UnifiedSessionManager.UnifiedTerminalSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriTemplate;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 终端 WebSocket Handler — 统一终端会话版本。
 * <p>
 * 不再自己创建 ChannelShell，改为委托 {@link UnifiedSessionManager}：
 * <ul>
 *   <li>连接建立 → {@link UnifiedSessionManager#getOrCreate} 获取共享会话</li>
 *   <li>注册监听器，实时推送终端输出到 WebSocket</li>
 *   <li>用户输入 → {@link UnifiedTerminalSession#writeUserInput}（AI 操作时自动丢弃）</li>
 *   <li>连接断开 → 移除监听器（不关闭会话，AI 可能还在用）</li>
 * </ul>
 */
@Slf4j
@Component
public class SshTerminalWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private UnifiedSessionManager sessionManager;

    /** WebSocket 会话 ID → connectionId */
    private final Map<String, String> wsToConnection = new ConcurrentHashMap<>();

    /** WebSocket 会话 ID → 输出监听器（用于断开时移除） */
    private final Map<String, ListenerHolder> wsToListener = new ConcurrentHashMap<>();

    /** 单连接同步锁，保证 WebSocket sendMessage 线程安全 */
    private static final Object SEND_LOCK = new Object();

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        var template = new UriTemplate("/ws/terminal/{connectionId}");
        Map<String, String> vars = template.match(wsSession.getUri().getPath());
        String connectionId = vars.get("connectionId");

        log.info("WebSocket 终端连接 connectionId={} wsSessionId={}", connectionId, wsSession.getId());

        wsToConnection.put(wsSession.getId(), connectionId);

        // 获取或创建统一终端会话（传入监听器确保 MOTD 也能收到）
        var listener = new ListenerHolder(wsSession);
        try {
            sessionManager.getOrCreate(connectionId, 80, 24, listener);
        } catch (Exception e) {
            log.error("获取终端会话失败 connectionId={}", connectionId, e);
            sendMessage(wsSession, "[31mSSH 会话不存在或已断开，请先连接服务器[0m\r\n");
            wsSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        wsToListener.put(wsSession.getId(), listener);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        String connectionId = wsToConnection.get(wsSession.getId());
        if (connectionId == null) return;

        try {
            var session = sessionManager.getSession(connectionId);
            log.debug("WS输入 connectionId={} data='{}'", connectionId, message.getPayload());
            session.writeUserInput(message.getPayload());
        } catch (IllegalArgumentException e) {
            log.warn("终端会话不存在 wsSessionId={}", wsSession.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        String connectionId = wsToConnection.remove(wsSession.getId());
        var holder = wsToListener.remove(wsSession.getId());
        if (connectionId != null && holder != null) {
            try {
                var session = sessionManager.getSession(connectionId);
                session.removeListener(holder);
            } catch (IllegalArgumentException ignored) {
                // session 可能已被清理
            }
        }
        log.info("WebSocket 终端断开 connectionId={} wsSessionId={}", connectionId, wsSession.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("WebSocket 传输错误 wsSessionId={}", wsSession.getId(), exception);
        afterConnectionClosed(wsSession, CloseStatus.SERVER_ERROR);
    }

    // ==================== 内部 ====================

    /** 线程安全发送消息到 WebSocket */
    private static void sendMessage(WebSocketSession ws, String text) {
        synchronized (SEND_LOCK) {
            if (ws.isOpen()) {
                try {
                    ws.sendMessage(new TextMessage(text));
                } catch (IOException e) {
                    log.warn("WebSocket 发送失败", e);
                }
            }
        }
    }

    /** 监听器包装器，持有 WebSocket 引用并通过同步锁发送 */
    private static class ListenerHolder implements java.util.function.Consumer<String> {
        private final WebSocketSession wsSession;

        ListenerHolder(WebSocketSession wsSession) {
            this.wsSession = wsSession;
        }

        @Override
        public void accept(String text) {
            sendMessage(wsSession, text);
        }
    }
}

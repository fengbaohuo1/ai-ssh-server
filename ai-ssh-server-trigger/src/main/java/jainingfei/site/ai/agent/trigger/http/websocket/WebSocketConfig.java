package jainingfei.site.ai.agent.trigger.http.websocket;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private SshTerminalWebSocketHandler terminalHandler;

    @Resource
    private SecurityProperties securityProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 与 HTTP CORS 共用同一份白名单：不再无条件允许任意来源发起终端握手
        registry.addHandler(terminalHandler, "/ws/terminal/{connectionId}")
                .addInterceptors(new TerminalAuthHandshakeInterceptor(securityProperties))
                .setAllowedOrigins(securityProperties.getAllowedOrigins().toArray(new String[0]));
    }
}

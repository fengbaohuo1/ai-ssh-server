package jainingfei.site.ai.agent.trigger.http.websocket;

import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 终端 WebSocket 握手鉴权。
 *
 * <p>浏览器 WebSocket API 不能自定义 Header，因此令牌通过查询参数 {@code access_token} 传递
 * （也兼容反向代理注入的 {@code X-AI-SSH-Token} 头）。未开启鉴权时直接放行。
 *
 * <p>注意：查询参数可能出现在访问日志中，生产环境建议配合反向代理 / TLS 使用。
 */
@Slf4j
public class TerminalAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final SecurityProperties securityProperties;

    public TerminalAuthHandshakeInterceptor(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!securityProperties.isAuthEnabled()) {
            return true;
        }
        if (!securityProperties.hasApiToken()) {
            log.warn("终端 WebSocket 鉴权已开启但未配置 api-token，拒绝握手");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = queryParam(request, "access_token");
        if (token == null) {
            token = request.getHeaders().getFirst("X-AI-SSH-Token");
        }
        if (token == null || !constantTimeEquals(token.trim(), securityProperties.getApiToken())) {
            log.warn("终端 WebSocket 握手鉴权失败 uri={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String queryParam(ServerHttpRequest request, String name) {
        String query = request.getURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}

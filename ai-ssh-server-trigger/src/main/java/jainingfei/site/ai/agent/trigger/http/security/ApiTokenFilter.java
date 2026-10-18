package jainingfei.site.ai.agent.trigger.http.security;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 接口令牌过滤器——可选的本地/内网访问控制。
 *
 * <p>开启方式：{@code ai.security.auth-enabled=true} 且配置 {@code ai.security.api-token}。
 * 令牌读取顺序：{@code X-AI-SSH-Token} → {@code Authorization: Bearer} → {@code access_token} 查询参数。
 *
 * <p>白名单：{@code ai.security.public-endpoints}（默认健康检查）与所有 OPTIONS 预检请求。
 * 注意：WebSocket 终端握手不在此过滤器范围（路径 /ws/**）；生产部署应限制监听地址或加反向代理鉴权。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-AI-SSH-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    @Resource
    private SecurityProperties securityProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!securityProperties.isAuthEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        for (String publicPath : securityProperties.getPublicEndpoints()) {
            if (publicPath != null && !publicPath.isBlank() && uri.startsWith(publicPath.trim())) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String token = resolveToken(request);
        if (!securityProperties.hasApiToken()) {
            log.warn("接口鉴权已开启但未配置 ai.security.api-token，所有受保护接口将拒绝访问");
            reject(response);
            return;
        }
        if (token == null || !constantTimeEquals(token, securityProperties.getApiToken())) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        String queryToken = request.getParameter("access_token");
        return queryToken == null || queryToken.isBlank() ? null : queryToken.trim();
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"401\",\"info\":\"未授权：缺少或错误的接口令牌\",\"data\":null}");
    }
}

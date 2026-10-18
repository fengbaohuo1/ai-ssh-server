package jainingfei.site.ai.agent.trigger.http.security;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jainingfei.site.ai.agent.domain.security.support.AuditContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计上下文过滤器——把 userId / clientIp 放进 ThreadLocal，供领域服务读取。
 *
 * <p>单用户阶段 userId 取 {@code X-User-Id} 头或 {@code userId} 查询参数，缺省 default；
 * 接入真实登录体系后，这里替换成"从会话/令牌解析用户"即可，领域层无感。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        if ((userId == null || userId.isBlank()) && !isMultipart(request)) {
            // multipart 请求不要触发 getParameter：Tomcat 会提前解析表单，
            // 与后续 MultipartFile 读取争抢输入流（上传会偶发失败）
            userId = request.getParameter("userId");
        }
        AuditContext.set(userId, resolveClientIp(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

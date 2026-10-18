package jainingfei.site.ai.agent.trigger.http.security;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 白名单——默认只放行本机前端，替代原先散落各控制器的 {@code @CrossOrigin(origins="*")}。
 *
 * <p>允许通过 {@code ai.security.allowed-origins} 覆盖（例如部署到内网域名）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Resource
    private SecurityProperties securityProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(securityProperties.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

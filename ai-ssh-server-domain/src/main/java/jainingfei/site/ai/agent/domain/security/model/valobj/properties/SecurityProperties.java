package jainingfei.site.ai.agent.domain.security.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台安全配置——凭证加密、接口鉴权、SFTP 边界与审计开关集中一处。
 *
 * <pre>
 *   credential-key     AES-256-GCM 主密钥（Base64 / 64 位 hex / ≥32 字节原文）；留空使用开发默认密钥
 *   api-token          接口令牌（X-AI-SSH-Token 或 Authorization: Bearer）
 *   auth-enabled       是否启用接口鉴权（默认关闭，保持本地开发零配置）
 *   allowed-origins    CORS 白名单（默认只放行本机前端）
 *   sftp.*             文件系统安全边界（预览上限、上传上限、禁止目录、通道超时）
 *   audit.*            操作审计开关与查询上限
 * </pre>
 *
 * <p>生产环境请通过环境变量注入：
 * {@code AI_SECURITY_CREDENTIAL_KEY} / {@code AI_SECURITY_API_TOKEN} / {@code AI_SECURITY_AUTH_ENABLED}。
 */
@Data
@ConfigurationProperties(prefix = "ai.security", ignoreInvalidFields = true)
public class SecurityProperties {

    /** 凭证加密主密钥；空 = 使用开发默认密钥（会在启动日志中告警） */
    private String credentialKey = "";

    /** 接口访问令牌；空且 auth-enabled=true 时启动告警（所有受保护接口将拒绝访问） */
    private String apiToken = "";

    /** 是否启用接口令牌鉴权 */
    private boolean authEnabled = false;

    /** 无需鉴权的路径前缀（默认健康检查） */
    private List<String> publicEndpoints = new ArrayList<>(List.of("/api/v1/system/health"));

    /** CORS 允许来源；默认只放行本机前端（Vite / Tauri） */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:1420",
            "http://127.0.0.1:1420",
            "http://tauri.localhost",
            "tauri://localhost",
            "https://tauri.localhost"
    ));

    private Sftp sftp = new Sftp();

    private Audit audit = new Audit();

    /** SFTP 安全边界 */
    @Data
    public static class Sftp {
        /** 文本预览最大字节数 */
        private int maxPreviewBytes = 256 * 1024;
        /** 单次上传最大字节数 */
        private long maxUploadBytes = 100L * 1024 * 1024;
        /** 允许单次列出的最大条目数（防御超大目录） */
        private int maxListEntries = 5000;
        /** 递归删除最大深度 */
        private int maxDeleteDepth = 32;
        /** SFTP 通道连接超时（秒） */
        private int timeoutSeconds = 15;
        /** 禁止访问的目录前缀（按规范化后的绝对路径匹配） */
        private List<String> blockedPaths = new ArrayList<>(List.of("/proc", "/sys", "/dev"));
    }

    /** 审计配置 */
    @Data
    public static class Audit {
        /** 是否记录操作审计 */
        private boolean enabled = true;
        /** 单次查询最大条数 */
        private int maxQueryLimit = 200;
    }

    /** 是否显式配置了主密钥（false 表示使用开发默认密钥） */
    public boolean hasConfiguredCredentialKey() {
        return credentialKey != null && !credentialKey.isBlank();
    }

    /** 是否已配置接口令牌 */
    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }
}

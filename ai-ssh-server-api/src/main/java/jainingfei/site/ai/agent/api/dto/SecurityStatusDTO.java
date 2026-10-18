package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 平台安全状态 DTO——设置页 / 运维首页展示"当前防护处于什么状态"。
 */
@Data
public class SecurityStatusDTO {

    /** 接口鉴权是否开启 */
    private boolean authEnabled;

    /** 是否配置了接口令牌 */
    private boolean apiTokenConfigured;

    /** 是否显式配置了凭证主密钥 */
    private boolean credentialKeyConfigured;

    /** 凭证加密算法 */
    private String credentialAlgorithm;

    /** 明文接口路径白名单 */
    private List<String> publicEndpoints;

    /** CORS 允许来源 */
    private List<String> allowedOrigins;

    /** 上传上限（字节） */
    private long maxUploadBytes;

    /** 预览上限（字节） */
    private int maxPreviewBytes;

    /** 禁止访问的目录 */
    private List<String> blockedPaths;

    /** 审计是否开启 */
    private boolean auditEnabled;
}

package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 平台运行概览 DTO——运维首页 / 状态栏的数据源。
 */
@Data
public class SystemOverviewDTO {

    /** 服务端时间 */
    private String serverTime;

    /** 应用版本 */
    private String version;

    /** 运行时长（秒） */
    private long uptimeSeconds;

    /** Java 版本 */
    private String javaVersion;

    /** 操作系统 */
    private String osName;

    /** 数据库是否可用 */
    private boolean databaseReachable;

    /** 连接总数（当前用户） */
    private long totalConnections;

    /** 已建立的 SSH 会话数 */
    private int activeSshSessions;

    /** 活跃终端会话数 */
    private int activeTerminals;

    /** 已装配智能体数 */
    private int configuredAgents;

    /** 已装配智能体名 */
    private List<String> agentNames;

    /** 审计是否开启 */
    private boolean auditEnabled;

    /** 接口鉴权是否开启 */
    private boolean authEnabled;

    /** 是否显式配置了凭证主密钥 */
    private boolean credentialKeyConfigured;

    /** 是否配置了接口令牌 */
    private boolean apiTokenConfigured;

    /** 审计动作计数（近 24h，key=action） */
    private Map<String, Long> auditCountsLast24h;
}

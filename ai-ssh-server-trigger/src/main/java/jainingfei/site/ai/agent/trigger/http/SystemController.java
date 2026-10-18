package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.SecurityStatusDTO;
import jainingfei.site.ai.agent.api.dto.SystemOverviewDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.security.model.entity.AuditLogEntity;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.security.service.IAuditService;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.infrastructure.adapter.session.UnifiedSessionManager;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台运行状态 / 安全姿态接口——运维首页、状态栏与设置页的数据源。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    public static final String VERSION = "2.0.0";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private ISshSessionPort sshSessionPort;

    @Resource
    private UnifiedSessionManager unifiedSessionManager;

    @Resource
    private AiAgentAutoConfigProperties agentConfigProperties;

    @Resource
    private SecurityProperties securityProperties;

    @Resource
    private IAuditService auditService;

    @Resource
    private DataSource dataSource;

    /** 完整概览（运维面板） */
    @GetMapping("/overview")
    public Response<SystemOverviewDTO> overview(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        try {
            SystemOverviewDTO dto = new SystemOverviewDTO();
            dto.setServerTime(LocalDateTime.now().format(TIME_FORMAT));
            dto.setVersion(VERSION);
            dto.setUptimeSeconds(uptimeSeconds());
            dto.setJavaVersion(System.getProperty("java.version"));
            dto.setOsName(System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            dto.setDatabaseReachable(databaseReachable());
            dto.setTotalConnections(sshConnectionService.queryUserConnections(userId).size());
            dto.setActiveSshSessions(sshSessionPort.activeConnectionCount());
            dto.setActiveTerminals(unifiedSessionManager.activeSessionCount());
            dto.setAuditEnabled(securityProperties.getAudit().isEnabled());
            dto.setAuthEnabled(securityProperties.isAuthEnabled());
            dto.setCredentialKeyConfigured(securityProperties.hasConfiguredCredentialKey());
            dto.setApiTokenConfigured(securityProperties.hasApiToken());

            List<String> agentNames = new ArrayList<>();
            if (agentConfigProperties.getTables() != null) {
                for (AiAgentConfigTableVO table : agentConfigProperties.getTables().values()) {
                    if (table != null && table.getAgent() != null && table.getAgent().getAgentName() != null) {
                        agentNames.add(table.getAgent().getAgentName());
                    }
                }
            }
            dto.setConfiguredAgents(agentNames.size());
            dto.setAgentNames(agentNames);
            dto.setAuditCountsLast24h(auditCountsLast24h());
            return ok(dto);
        } catch (Exception e) {
            log.error("查询运行概览失败", e);
            return fail();
        }
    }

    /** 健康检查（默认免鉴权，供探针使用） */
    @GetMapping("/health")
    public Response<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean db = databaseReachable();
        data.put("status", db ? "UP" : "DEGRADED");
        data.put("version", VERSION);
        data.put("database", db ? "UP" : "DOWN");
        data.put("uptimeSeconds", uptimeSeconds());
        data.put("serverTime", LocalDateTime.now().format(TIME_FORMAT));
        return Response.<Map<String, Object>>builder()
                .code(db ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(db ? "UP" : "数据库不可用")
                .data(data)
                .build();
    }

    /** 安全姿态（设置页展示） */
    @GetMapping("/security")
    public Response<SecurityStatusDTO> security() {
        try {
            SecurityStatusDTO dto = new SecurityStatusDTO();
            dto.setAuthEnabled(securityProperties.isAuthEnabled());
            dto.setApiTokenConfigured(securityProperties.hasApiToken());
            dto.setCredentialKeyConfigured(securityProperties.hasConfiguredCredentialKey());
            dto.setCredentialAlgorithm("AES-256-GCM");
            dto.setPublicEndpoints(securityProperties.getPublicEndpoints());
            dto.setAllowedOrigins(securityProperties.getAllowedOrigins());
            dto.setMaxUploadBytes(securityProperties.getSftp().getMaxUploadBytes());
            dto.setMaxPreviewBytes(securityProperties.getSftp().getMaxPreviewBytes());
            dto.setBlockedPaths(securityProperties.getSftp().getBlockedPaths());
            dto.setAuditEnabled(securityProperties.getAudit().isEnabled());
            return ok(dto);
        } catch (Exception e) {
            log.error("查询安全姿态失败", e);
            return fail();
        }
    }

    // ==================== 内部 ====================

    private long uptimeSeconds() {
        return (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000L;
    }

    private boolean databaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            log.warn("数据库健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, Long> auditCountsLast24h() {
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            List<AuditLogEntity> logs = auditService.query(null, null, null, null, 200, 0);
            LocalDateTime since = LocalDateTime.now().minusHours(24);
            for (AuditLogEntity log : logs) {
                if (log.getCreatedAt() != null && log.getCreatedAt().isBefore(since)) {
                    continue;
                }
                String key = log.getAction() == null ? "UNKNOWN" : log.getAction().name();
                counts.merge(key, 1L, Long::sum);
            }
        } catch (Exception e) {
            log.debug("统计近 24h 审计失败: {}", e.getMessage());
        }
        return counts;
    }

    private static <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static <T> Response<T> fail() {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(ResponseCode.UN_ERROR.getInfo())
                .build();
    }
}

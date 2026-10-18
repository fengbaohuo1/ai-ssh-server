package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.ISshConnectionApiService;
import jainingfei.site.ai.agent.api.dto.SshConnectionCreateRequestDTO;
import jainingfei.site.ai.agent.api.dto.SshConnectionResponseDTO;
import jainingfei.site.ai.agent.api.dto.SshConnectionUpdateRequestDTO;
import jainingfei.site.ai.agent.api.dto.SshGroupDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.infrastructure.adapter.session.UnifiedSessionManager;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SSH 连接 HTTP 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/connection")
public class SshConnectionController implements ISshConnectionApiService {

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private UnifiedSessionManager unifiedSessionManager;

    @PostMapping("/create")
    @Override
    public Response<SshConnectionResponseDTO> createConnection(@RequestBody SshConnectionCreateRequestDTO request) {
        try {
            log.info("创建SSH连接配置 connectionName={} host={}", request.getConnectionName(), request.getHost());

            // 1. 构建领域实体
            String connectionId = UUID.randomUUID().toString().replace("-", "");

            SshConnectionEntity connection = SshConnectionEntity.builder()
                    .connectionId(connectionId)
                    .connectionName(request.getConnectionName())
                    .host(request.getHost())
                    .port(request.getPort() != null ? request.getPort() : 22)
                    .username(request.getUsername())
                    .authType(AuthTypeEnum.fromCode(request.getAuthType()))
                    .password(request.getPassword())
                    .privateKey(request.getPrivateKey())
                    .encrypted(true)
                    .status(ConnectionStatusEnum.DISCONNECTED)
                    .userId(request.getUserId())
                    .groupName(request.getGroupName())
                    .tags(request.getTags() == null ? new ArrayList<>() : request.getTags())
                    .remark(request.getRemark())
                    .favorite(Boolean.TRUE.equals(request.getFavorite()))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .deleted(false)
                    .build();

            SshConnectionConfigEntity config = SshConnectionConfigEntity.builder()
                    .connectionId(connectionId)
                    .connectTimeout(request.getConnectTimeout() != null ? request.getConnectTimeout() : 10)
                    .keepaliveInterval(request.getKeepaliveInterval() != null ? request.getKeepaliveInterval() : 60)
                    .startupCommand(request.getStartupCommand())
                    .compression(request.getCompression() != null && request.getCompression() == 1)
                    .strictHostKeyCheck(request.getStrictHostKeyCheck() == null || request.getStrictHostKeyCheck() == 1)
                    .knownHosts(request.getKnownHosts())
                    .build();

            // 2. 调用领域服务创建
            SshConnectionAggregate aggregate = sshConnectionService.createConnection(connection, config);

            // 3. 构建响应
            SshConnectionResponseDTO responseDTO = toResponseDTO(aggregate);

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("创建SSH连接配置异常", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建SSH连接配置失败", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PutMapping("/{connectionId}")
    public Response<SshConnectionResponseDTO> updateConnection(@PathVariable String connectionId,
                                                               @RequestBody SshConnectionUpdateRequestDTO request) {
        try {
            log.info("更新SSH连接 connectionId={}", connectionId);

            SshConnectionEntity patch = SshConnectionEntity.builder()
                    .connectionName(request.getConnectionName())
                    .host(request.getHost())
                    .port(request.getPort())
                    .username(request.getUsername())
                    .authType(AuthTypeEnum.fromCode(request.getAuthType()))
                    .password(request.getPassword())
                    .privateKey(request.getPrivateKey())
                    .groupName(request.getGroupName())
                    .tags(request.getTags())
                    .remark(request.getRemark())
                    .favorite(request.getFavorite())
                    .build();

            SshConnectionConfigEntity configPatch = SshConnectionConfigEntity.builder()
                    .connectTimeout(request.getConnectTimeout())
                    .keepaliveInterval(request.getKeepaliveInterval())
                    .startupCommand(request.getStartupCommand())
                    .compression(request.getCompression() == null ? null : request.getCompression() == 1)
                    .strictHostKeyCheck(request.getStrictHostKeyCheck() == null ? null : request.getStrictHostKeyCheck() == 1)
                    .knownHosts(request.getKnownHosts())
                    .build();

            SshConnectionAggregate aggregate = sshConnectionService.updateConnection(connectionId, patch, configPatch);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponseDTO(aggregate))
                    .build();
        } catch (AppException e) {
            log.error("更新SSH连接异常", e);
            return Response.<SshConnectionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("更新SSH连接失败", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/groups")
    public Response<List<SshGroupDTO>> queryGroups(@RequestParam String userId) {
        try {
            List<SshConnectionEntity> connections = sshConnectionService.queryUserConnections(userId);
            Map<String, long[]> stats = new LinkedHashMap<>();
            for (SshConnectionEntity connection : connections) {
                String group = connection.getGroupName();
                if (group == null || group.isBlank()) {
                    group = "未分组";
                }
                long[] counter = stats.computeIfAbsent(group, key -> new long[2]);
                counter[0]++;
                if (Boolean.TRUE.equals(connection.getFavorite())) {
                    counter[1]++;
                }
            }
            List<SshGroupDTO> groups = stats.entrySet().stream()
                    .map(entry -> {
                        SshGroupDTO dto = new SshGroupDTO();
                        dto.setName(entry.getKey());
                        dto.setCount(entry.getValue()[0]);
                        dto.setFavoriteCount(entry.getValue()[1]);
                        return dto;
                    })
                    .sorted((a, b) -> {
                        if ("未分组".equals(a.getName())) return 1;
                        if ("未分组".equals(b.getName())) return -1;
                        return a.getName().compareTo(b.getName());
                    })
                    .collect(Collectors.toList());
            return Response.<List<SshGroupDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(groups)
                    .build();
        } catch (Exception e) {
            log.error("查询分组失败", e);
            return Response.<List<SshGroupDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/{connectionId}/connect")
    @Override
    public Response<Void> connect(@PathVariable String connectionId) {
        try {
            log.info("建立SSH连接 connectionId={}", connectionId);

            // 先清理旧终端会话（ChannelShell 绑定的是旧 JSch Session，重连后就失效了）
            unifiedSessionManager.closeSession(connectionId);

            boolean success = sshConnectionService.connect(connectionId);

            if (success) {
                return Response.<Void>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .build();
            } else {
                return Response.<Void>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("SSH连接失败，请检查主机地址、端口或认证信息")
                        .build();
            }
        } catch (AppException e) {
            log.error("建立SSH连接异常", e);
            return Response.<Void>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("建立SSH连接失败", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/{connectionId}/disconnect")
    @Override
    public Response<Void> disconnect(@PathVariable String connectionId) {
        try {
            log.debug("断开SSH连接 connectionId={}", connectionId);

            sshConnectionService.disconnect(connectionId);
            // 清理统一终端会话（ChannelShell）
            unifiedSessionManager.closeSession(connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (AppException e) {
            log.error("断开SSH连接异常", e);
            return Response.<Void>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("断开SSH连接失败", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/list")
    @Override
    public Response<List<SshConnectionResponseDTO>> queryUserConnections(@RequestParam String userId) {
        try {
            log.info("查询用户连接列表 userId={}", userId);

            List<SshConnectionEntity> connections = sshConnectionService.queryUserConnections(userId);

            if (connections == null || connections.isEmpty()) {
                return Response.<List<SshConnectionResponseDTO>>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(Collections.emptyList())
                        .build();
            }

            List<SshConnectionResponseDTO> responseDTOS = connections.stream()
                    .map(this::toResponseDTO)
                    .collect(Collectors.toList());

            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();
        } catch (AppException e) {
            log.error("查询用户连接列表异常", e);
            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询用户连接列表失败", e);
            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/{connectionId}")
    @Override
    public Response<SshConnectionResponseDTO> queryConnectionDetail(@PathVariable String connectionId) {
        try {
            log.info("查询连接详情 connectionId={}", connectionId);

            SshConnectionAggregate aggregate = sshConnectionService.queryConnectionDetail(connectionId);

            if (aggregate == null) {
                return Response.<SshConnectionResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }

            SshConnectionResponseDTO responseDTO = toResponseDTO(aggregate);

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询连接详情异常", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询连接详情失败", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/{connectionId}")
    @Override
    public Response<Void> deleteConnection(@PathVariable String connectionId) {
        try {
            log.info("删除SSH连接 connectionId={}", connectionId);

            sshConnectionService.deleteConnection(connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (AppException e) {
            log.error("删除SSH连接异常", e);
            return Response.<Void>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("删除SSH连接失败", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    // ==================== 转换方法 ====================

    private SshConnectionResponseDTO toResponseDTO(SshConnectionAggregate aggregate) {
        if (aggregate == null) return null;
        SshConnectionResponseDTO dto = toResponseDTO(aggregate.getConnection());
        if (aggregate.getConfig() != null) {
            dto.setConnectTimeout(aggregate.getConfig().getConnectTimeout());
            dto.setKeepaliveInterval(aggregate.getConfig().getKeepaliveInterval());
            dto.setStartupCommand(aggregate.getConfig().getStartupCommand());
            dto.setCompression(boolToInt(aggregate.getConfig().getCompression()));
            dto.setStrictHostKeyCheck(boolToInt(aggregate.getConfig().getStrictHostKeyCheck()));
            dto.setKnownHosts(aggregate.getConfig().getKnownHosts());
        }
        return dto;
    }

    private SshConnectionResponseDTO toResponseDTO(SshConnectionEntity entity) {
        if (entity == null) return null;
        SshConnectionResponseDTO dto = new SshConnectionResponseDTO();
        dto.setConnectionId(entity.getConnectionId());
        dto.setConnectionName(entity.getConnectionName());
        dto.setHost(entity.getHost());
        dto.setPort(entity.getPort());
        dto.setUsername(entity.getUsername());
        dto.setAuthType(entity.getAuthType() != null ? entity.getAuthType().getCode() : null);
        // 不返回密码/私钥等敏感信息
        dto.setStatus(entity.getStatus() != null ? entity.getStatus().getCode() : null);
        dto.setUserId(entity.getUserId());
        dto.setGroupName(entity.getGroupName());
        dto.setTags(entity.getTags() == null ? new ArrayList<>() : entity.getTags());
        dto.setRemark(entity.getRemark());
        dto.setFavorite(Boolean.TRUE.equals(entity.getFavorite()));
        dto.setLastConnectedAt(entity.getLastConnectedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private Integer boolToInt(Boolean value) {
        return value != null ? (value ? 1 : 0) : null;
    }
}

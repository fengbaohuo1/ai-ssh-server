package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.ISshConnectionApiService;
import jainingfei.site.ai.agent.api.dto.SshConnectionCreateRequestDTO;
import jainingfei.site.ai.agent.api.dto.SshConnectionResponseDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SSH 连接 HTTP 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/connection")
@CrossOrigin(origins = "*")
public class SshConnectionController implements ISshConnectionApiService {

    @Resource
    private ISshConnectionService sshConnectionService;

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

    @PostMapping("/{connectionId}/connect")
    @Override
    public Response<Void> connect(@PathVariable String connectionId) {
        try {
            log.info("建立SSH连接 connectionId={}", connectionId);

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
            log.info("断开SSH连接 connectionId={}", connectionId);

            sshConnectionService.disconnect(connectionId);

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
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private Integer boolToInt(Boolean value) {
        return value != null ? (value ? 1 : 0) : null;
    }
}

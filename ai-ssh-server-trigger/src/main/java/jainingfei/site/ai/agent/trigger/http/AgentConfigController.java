package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.IAgentConfigService;
import jainingfei.site.ai.agent.api.dto.AgentConfigFileDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigFileUpdateDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigModelDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigModelUpdateDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigWriteResultDTO;
import jainingfei.site.ai.agent.api.dto.AgentProviderOptionDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.infrastructure.config.AgentConfigFileStore;
import jainingfei.site.ai.agent.infrastructure.config.AgentConfigValidationException;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent / 模型配置接口——桌面端「Agent YAML 配置」与「模型通道配置」两个页面的数据源。
 *
 * <p>路径在 {@code /api/**} 下，自动被 {@code ApiTokenFilter} 覆盖（server profile 开启鉴权时生效；
 * local profile 本机单用户默认关闭）。
 *
 * <p>错误约定：配置校验失败返回 {@code code=0002} + 具体原因（HTTP 仍 200），
 * 其它异常返回 {@code code=0001}；成功写入返回 {@code restartRequired=true}。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/config")
public class AgentConfigController implements IAgentConfigService {

    @Resource
    private AgentConfigFileStore agentConfigFileStore;

    // ==================== Agent YAML 配置 ====================

    @GetMapping("/file")
    @Override
    public Response<AgentConfigFileDTO> queryAgentConfigFile() {
        try {
            AgentConfigFileDTO dto = new AgentConfigFileDTO();
            dto.setPath(agentConfigFileStore.agentConfigPathString());
            dto.setContent(agentConfigFileStore.readAgentContent());
            return ok(dto);
        } catch (Exception e) {
            log.error("读取 Agent 配置文件失败", e);
            return unError("读取 Agent 配置文件失败: " + e.getMessage());
        }
    }

    @PutMapping("/file")
    @Override
    public Response<AgentConfigWriteResultDTO> updateAgentConfigFile(@RequestBody AgentConfigFileUpdateDTO request) {
        try {
            agentConfigFileStore.writeAgentContent(request == null ? null : request.getContent());
            log.info("Agent 配置文件已保存，需重启生效");
            return ok(new AgentConfigWriteResultDTO(true));
        } catch (AgentConfigValidationException e) {
            log.warn("Agent 配置文件校验失败: {}", e.getMessage());
            return illegalParameter(e.getMessage());
        } catch (Exception e) {
            log.error("保存 Agent 配置文件失败", e);
            return unError("保存 Agent 配置文件失败: " + e.getMessage());
        }
    }

    // ==================== 模型通道配置 ====================

    @GetMapping("/model")
    @Override
    public Response<AgentConfigModelDTO> queryModelConfig() {
        try {
            AgentConfigFileStore.ModelConfigSnapshot snapshot = agentConfigFileStore.readModelConfig();

            AgentConfigModelDTO dto = new AgentConfigModelDTO();
            dto.setProvider(hasText(snapshot.provider())
                    ? snapshot.provider().trim() : ProviderCapabilities.OPENAI_COMPATIBLE);
            dto.setBaseUrl(snapshot.baseUrl());
            dto.setModel(snapshot.model());
            dto.setContextSize(snapshot.contextSize());
            dto.setReasoningEffort(snapshot.reasoningEffort());
            dto.setTemperature(snapshot.temperature());
            dto.setTopP(snapshot.topP());
            dto.setMaxTokens(snapshot.maxTokens());
            // 只回传"是否已配置"，不返回 key 明文
            dto.setApiKeyConfigured(snapshot.apiKeyConfigured());
            dto.setTableKey(snapshot.tableKey());
            dto.setConfigPath(snapshot.configPath());
            dto.setOverlayPath(snapshot.overlayPath());
            dto.setProviders(providerOptions());
            return ok(dto);
        } catch (Exception e) {
            log.error("读取模型配置失败", e);
            return unError("读取模型配置失败: " + e.getMessage());
        }
    }

    @PutMapping("/model")
    @Override
    public Response<AgentConfigWriteResultDTO> updateModelConfig(@RequestBody AgentConfigModelUpdateDTO request) {
        try {
            AgentConfigFileStore.ModelConfigUpdate update = new AgentConfigFileStore.ModelConfigUpdate(
                    request == null ? null : request.getProvider(),
                    request == null ? null : request.getBaseUrl(),
                    request == null ? null : request.getApiKey(),
                    request == null ? null : request.getModel(),
                    request == null ? null : request.getContextSize(),
                    request == null ? null : request.getReasoningEffort(),
                    request == null ? null : request.getTemperature(),
                    request == null ? null : request.getTopP(),
                    request == null ? null : request.getMaxTokens());
            agentConfigFileStore.writeModelConfig(update);
            log.info("模型配置已保存（写入 llm.yml 覆盖层），需重启生效");
            return ok(new AgentConfigWriteResultDTO(true));
        } catch (AgentConfigValidationException e) {
            log.warn("模型配置校验失败: {}", e.getMessage());
            return illegalParameter(e.getMessage());
        } catch (Exception e) {
            log.error("保存模型配置失败", e);
            return unError("保存模型配置失败: " + e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /** 通道下拉数据：唯一出处是 domain 的 ProviderCapabilities（deepseek-native / openai-compatible） */
    private static List<AgentProviderOptionDTO> providerOptions() {
        List<AgentProviderOptionDTO> options = new ArrayList<>();
        for (String providerId : List.of(ProviderCapabilities.DEEPSEEK_NATIVE,
                ProviderCapabilities.OPENAI_COMPATIBLE)) {
            ProviderCapabilities capabilities = ProviderCapabilities.forProvider(providerId);
            AgentProviderOptionDTO option = new AgentProviderOptionDTO();
            option.setId(capabilities.getProviderId());
            option.setDisplayName(capabilities.getDisplayName());
            option.setReasoningEffortValues(new ArrayList<>(capabilities.getReasoningEffortValues()));
            option.setNote(capabilities.getNote());
            options.add(option);
        }
        return options;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static <T> Response<T> illegalParameter(String info) {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(info)
                .build();
    }

    private static <T> Response<T> unError(String info) {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(info)
                .build();
    }
}

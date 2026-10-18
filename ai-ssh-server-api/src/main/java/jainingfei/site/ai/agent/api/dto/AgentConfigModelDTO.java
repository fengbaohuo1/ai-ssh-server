package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型通道配置响应（GET /api/v1/agent/config/model）。
 *
 * <p>安全约束：<b>绝不回传 api-key 明文</b>，只给 {@code apiKeyConfigured} 布尔位。
 */
@Data
public class AgentConfigModelDTO {

    /** 模型通道 id（deepseek-native / openai-compatible） */
    private String provider;

    /** 服务端点（OpenAI 兼容协议或厂商原生端点） */
    private String baseUrl;

    /** 模型名 */
    private String model;

    /** 上下文窗口（tokens），仅展示用 */
    private Long contextSize;

    /** 推理强度（各通道取值集合见 providers[].reasoningEffortValues） */
    private String reasoningEffort;

    /** 采样温度 */
    private Double temperature;

    /** 核采样 top-p */
    private Double topP;

    /** 单次回复最大 token 数 */
    private Integer maxTokens;

    /** 是否已配置 api-key（不返回 key 本身） */
    private boolean apiKeyConfigured;

    /** 当前生效的配置表 key（ai.agent.config.tables.<tableKey>） */
    private String tableKey;

    /** 主配置路径（agent.yml） */
    private String configPath;

    /** 模型覆盖层路径（llm.yml） */
    private String overlayPath;

    /** 已注册模型通道（能力声明来自 domain 的 ProviderCapabilities） */
    private List<AgentProviderOptionDTO> providers = new ArrayList<>();
}

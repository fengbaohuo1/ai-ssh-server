package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * 模型配置保存请求（PUT /api/v1/agent/config/model）。
 *
 * <p>只写 ${AI_SSH_HOME}/config/llm.yml 覆盖层的
 * {@code ai.agent.config.tables.<tableKey>.module.chat-model} 节点；agent.yml 不动。
 * {@code apiKey} 为空表示沿用现有 key（当前没有 key 时返回参数错误）。
 */
@Data
public class AgentConfigModelUpdateDTO {

    /** 模型通道 id，必须是已注册通道 */
    private String provider;

    private String baseUrl;

    /** 为空 = 沿用现有 key */
    private String apiKey;

    private String model;

    private Long contextSize;

    private String reasoningEffort;

    private Double temperature;

    private Double topP;

    private Integer maxTokens;
}

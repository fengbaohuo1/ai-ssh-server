package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * Agent 主配置保存请求（PUT /api/v1/agent/config/file）。
 */
@Data
public class AgentConfigFileUpdateDTO {

    /** 新 YAML 全文 */
    private String content;
}

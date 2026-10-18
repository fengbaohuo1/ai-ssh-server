package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * Agent 主配置（agent.yml）原文响应——配置页只读/可编辑 YAML 用。
 */
@Data
public class AgentConfigFileDTO {

    /** 配置文件的绝对路径（正斜杠） */
    private String path;

    /** 原始 YAML 全文（不解析、不改写注释） */
    private String content;
}

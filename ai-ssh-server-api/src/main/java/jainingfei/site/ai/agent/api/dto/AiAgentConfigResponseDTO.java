package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能体配置响应对象
 * <p>
 * 除基础 agentId/agentName/agentDesc 外，回传配置文件（agent/*.yml）中的真实模型与能力信息，
 * 供前端只读展示：
 * <ul>
 *   <li>chatModel：模型名 / baseUrl / 上下文窗口（contextSize）/ 推理强度（reasoningEffort）——<b>不含 apiKey</b></li>
 *   <li>subAgents / agentWorkflows：模块内子智能体与工作流</li>
 *   <li>mcpTools / skills / plugins：MCP 工具、技能目录、运行插件（未配置为空列表）</li>
 * </ul>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:18
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 应用名（配置文件 tables.*.app-name）
     */
    private String appName;

    /**
     * 模型配置（配置文件 tables.*.module.chat-model，剔除 apiKey）
     */
    private ChatModelInfo chatModel;

    /**
     * 子智能体名称列表（module.agents[].name）
     */
    private List<String> subAgents;

    /**
     * 工作流列表（module.agent-workflows）
     */
    private List<AgentWorkflowInfo> agentWorkflows;

    /**
     * MCP 工具列表（chat-model.tool-mcp-list，未配置为空）
     */
    private List<McpToolInfo> mcpTools;

    /**
     * 技能列表（chat-model.tool-skills-list，未配置为空）
     */
    private List<SkillInfo> skills;

    /**
     * 运行插件列表（runner.plugin-name-list）
     */
    private List<String> plugins;

    @Data
    public static class ChatModelInfo {
        /** 模型名（chat-model.model） */
        private String modelName;
        /** 服务地址（chat-model.base-url） */
        private String baseUrl;
        /** 模型上下文窗口（tokens，chat-model.context-size） */
        private Long contextSize;
        /** 推理强度（chat-model.reasoning-effort，可为空=未配置） */
        private String reasoningEffort;
    }

    @Data
    public static class AgentWorkflowInfo {
        /** 类型：loop / parallel / sequential */
        private String type;
        private String name;
        private String description;
    }

    @Data
    public static class McpToolInfo {
        /** 工具名 */
        private String name;
        /** 连接类型：remote / stdio / local */
        private String kind;
        /** 目标：remote 为 url；stdio 为启动命令；local 无 */
        private String target;
    }

    @Data
    public static class SkillInfo {
        /** 类型：directory / resource */
        private String type;
        /** 路径 */
        private String path;
    }

}

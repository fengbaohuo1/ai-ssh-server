package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * MCP 工具创建策略接口，每种 MCP 模式（remote/stdio/local）返回 {@link ToolCallback}[]，
 * 统一通过 {@code ToolCallingManager} 注入 {@code OpenAiChatModel}，不再走 ADK BaseToolset。
 * 返回 null 表示该策略不处理当前配置。
 */
public interface ToolMcpCreateService {
    ToolCallback[] createToolCallbacks(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp);
}

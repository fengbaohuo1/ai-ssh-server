package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client;

import com.google.adk.tools.BaseToolset;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;

/**
 * MCP 工具创建策略接口，每种 MCP 模式（remote/stdio/local）返回 ADK {@link BaseToolset}。
 * 返回 null 表示该策略不处理当前配置。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}，交给 ADK 管理工具调用。
 */
public interface ToolMcpCreateService {
    BaseToolset createToolset(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp);
}

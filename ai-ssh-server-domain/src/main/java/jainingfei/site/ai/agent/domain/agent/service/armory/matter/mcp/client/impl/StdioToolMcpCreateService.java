package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Stdio MCP 工具创建策略：启动本地进程，通过标准输入输出通信。
 * 使用 {@link SyncMcpToolCallbackProvider} 将 MCP 工具转为 {@link ToolCallback}[]。
 */
@Slf4j
@Service
public class StdioToolMcpCreateService implements ToolMcpCreateService {

    @Override
    public ToolCallback[] createToolCallbacks(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdioConfig = toolMcp.getStdio();
        if (stdioConfig == null) {
            return null;
        }

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters configParams = stdioConfig.getServerParameters();

        ServerParameters stdioParams = ServerParameters.builder(configParams.getCommand())
                .args(configParams.getArgs())
                .env(configParams.getEnv())
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(
                        new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new JsonMapper())))
                .requestTimeout(Duration.ofMillis(stdioConfig.getRequestTimeout()))
                .build();

        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();
        log.info("stdio mcp [{}] initialized: {}", stdioConfig.getName(), initialize);

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient)
                .build()
                .getToolCallbacks();
    }
}

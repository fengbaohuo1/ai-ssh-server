package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Remote MCP 工具创建策略：通过 Streamable HTTP 连接远程 MCP Server，
 * 使用 {@link SyncMcpToolCallbackProvider} 将 MCP 工具转为 {@link ToolCallback}[]。
 */
@Slf4j
@Service
public class RemoteToolMcpCreateService implements ToolMcpCreateService {

    @Override
    public ToolCallback[] createToolCallbacks(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.RemoteServerParameters remoteConfig = toolMcp.getRemote();
        if (remoteConfig == null) {
            return null;
        }

        URI fullUri = java.net.URI.create(remoteConfig.getUrl());
        String baseUri = fullUri.getScheme() + "://" + fullUri.getHost()
                + (fullUri.getPort() > 0 ? ":" + fullUri.getPort() : "");
        String rawPath = fullUri.getRawPath();
        String endpoint = (rawPath != null && !rawPath.isBlank()) ? rawPath : "/";

        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport
                .builder(baseUri)
                .endpoint(endpoint)
                .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)))
                .jsonMapper(new JacksonMcpJsonMapper(new JsonMapper()));

        Map<String, String> headers = remoteConfig.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            transportBuilder.httpRequestCustomizer((builder, method, uri, body, context) ->
                    headers.forEach(builder::header));
        }

        HttpClientStreamableHttpTransport transport = transportBuilder.build();
        McpSyncClient mcpSyncClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(remoteConfig.getRequestTimeout()))
                .build();

        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();
        log.info("remote mcp [{}] initialized: {}", remoteConfig.getName(), initialize);

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient)
                .build()
                .getToolCallbacks();
    }
}

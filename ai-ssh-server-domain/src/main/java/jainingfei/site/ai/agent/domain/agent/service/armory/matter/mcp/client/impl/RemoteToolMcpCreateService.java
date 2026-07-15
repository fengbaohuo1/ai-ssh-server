package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remote MCP 工具创建策略：Streamable HTTP → ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}，使用 MCP SDK 原生 API
 * 连接远端 Server，将 MCP Tool 包装为 ADK {@link BaseTool}。
 */
@Slf4j
@Service
public class RemoteToolMcpCreateService implements ToolMcpCreateService {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new JsonMapper());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public BaseToolset createToolset(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.RemoteServerParameters cfg = toolMcp.getRemote();
        if (cfg == null) return null;
        return new RemoteMcpToolsetImpl(cfg.getName(), cfg.getUrl(), cfg.getHeaders(),
                Duration.ofMillis(cfg.getRequestTimeout()));
    }

    // ===== 内部：Toolset + Tool 实现 =====

    private static class RemoteMcpToolsetImpl implements BaseToolset {
        private final String name, url;
        private final Map<String, String> headers;
        private final Duration timeout;
        private volatile McpSyncClient session;
        private volatile List<BaseTool> cachedTools;

        RemoteMcpToolsetImpl(String name, String url, Map<String, String> headers, Duration timeout) {
            this.name = name; this.url = url; this.headers = headers; this.timeout = timeout;
        }

        @Override
        public Flowable<BaseTool> getTools(ReadonlyContext ctx) {
            if (cachedTools != null) return Flowable.fromIterable(cachedTools);
            return Flowable.defer(() -> {
                try {
                    if (session == null) session = connect();
                    List<McpSchema.Tool> tools = session.listTools().tools();
                    cachedTools = new ArrayList<>();
                    for (McpSchema.Tool t : tools) cachedTools.add(new McpToolAdapter(t, session));
                    log.info("Remote MCP [{}] 发现 {} 个工具", name, cachedTools.size());
                    return Flowable.fromIterable(cachedTools);
                } catch (Exception e) {
                    log.error("Remote MCP [{}] 加载工具失败", name, e);
                    return Flowable.error(e);
                }
            });
        }

        private McpSyncClient connect() throws Exception {
            URI uri = URI.create(url);
            String base = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String endpoint = (uri.getRawPath() != null && !uri.getRawPath().isBlank()) ? uri.getRawPath() : "/";
            var b = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint)
                    .connectTimeout(timeout).jsonMapper(JSON_MAPPER);
            if (headers != null && !headers.isEmpty())
                b.httpRequestCustomizer((rb, m, u, body, c) -> headers.forEach(rb::header));
            McpSyncClient c = McpClient.sync(b.build()).requestTimeout(timeout).build();
            c.initialize();
            log.info("Remote MCP [{}] session 已建立", name);
            return c;
        }

        @Override public void close() { if (session != null) try { session.close(); } catch (Exception ignored) {} }
    }

    private static class McpToolAdapter extends BaseTool {
        private final McpSchema.Tool tool;
        private final McpSyncClient session;
        private final FunctionDeclaration declaration;

        McpToolAdapter(McpSchema.Tool tool, McpSyncClient session) {
            super(tool.name(), tool.description() != null ? tool.description() : "");
            this.tool = tool; this.session = session;
            this.declaration = FunctionDeclaration.builder()
                    .name(tool.name()).description(tool.description() != null ? tool.description() : "")
                    .parametersJsonSchema(tool.inputSchema()).build();
            log.info("MCP tool [{}] schema: {}", tool.name(), tool.inputSchema() != null ? "present" : "NULL");
        }

        @Override public Optional<FunctionDeclaration> declaration() { return Optional.of(declaration); }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext ctx) {
            return Single.fromCallable(() -> {
                McpSchema.CallToolResult r = session.callTool(new McpSchema.CallToolRequest(tool.name(), args));
                String content = r.content() != null ? r.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text()).reduce("", String::concat) : null;
                if (content == null) return Map.of("result", "ok");
                try { return OBJECT_MAPPER.readValue(content, Map.class); }
                catch (Exception e) { return Map.of("result", content); }
            });
        }
    }
}

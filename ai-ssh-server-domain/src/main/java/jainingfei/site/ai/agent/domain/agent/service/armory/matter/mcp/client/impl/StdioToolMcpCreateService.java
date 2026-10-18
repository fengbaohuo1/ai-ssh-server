package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stdio MCP 工具创建策略：本地进程 stdio → ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}。
 */
@Slf4j
@Service
public class StdioToolMcpCreateService implements ToolMcpCreateService {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new JsonMapper());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 加载失败后的冷却窗口：期间不再重试，避免每次 LLM 调用都卡在进程启动/握手超时上 */
    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    @Override
    public BaseToolset createToolset(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters cfg = toolMcp.getStdio();
        if (cfg == null) return null;

        var sp = cfg.getServerParameters();
        ServerParameters params = ServerParameters.builder(sp.getCommand())
                .args(sp.getArgs()).env(sp.getEnv()).build();
        String name = cfg.getName();
        Duration timeout = Duration.ofMillis(cfg.getRequestTimeout());

        return new BaseToolset() {
            private volatile McpSyncClient session;
            /** 最近一次加载失败的时间戳（0 = 从未失败） */
            private volatile long lastFailureAt;

            /**
             * 取工具列表：<b>失败降级为空工具集</b>（与 remote 策略一致）。
             * <p>stdio MCP 是外部进程，可能没装、启动失败或握手超时；一次失败不该让整轮对话失败。
             */
            @Override
            public Flowable<BaseTool> getTools(ReadonlyContext ctx) {
                if (lastFailureAt > 0 && System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) {
                    return Flowable.empty();
                }
                return Flowable.defer(() -> {
                    try {
                        if (session == null) {
                            session = McpClient.sync(new StdioClientTransport(params, JSON_MAPPER))
                                    .requestTimeout(timeout).build();
                            session.initialize();
                            log.info("Stdio MCP [{}] session 已建立", name);
                        }
                        List<McpSchema.Tool> tools = session.listTools().tools();
                        lastFailureAt = 0;
                        log.info("Stdio MCP [{}] 发现 {} 个工具", name, tools.size());
                        return Flowable.fromIterable(tools.stream().map(t -> new BaseTool(t.name(),
                                t.description() != null ? t.description() : "") {
                            private final FunctionDeclaration decl = FunctionDeclaration.builder()
                                    .name(t.name()).description(t.description() != null ? t.description() : "")
                                    .parametersJsonSchema(t.inputSchema()).build();
                            @Override public Optional<FunctionDeclaration> declaration() { return Optional.of(decl); }
                            @Override
                            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext ctx) {
                                return Single.fromCallable(() -> {
                                    McpSchema.CallToolResult r = session.callTool(new McpSchema.CallToolRequest(t.name(), args));
                                    String c = r.content() != null ? r.content().stream()
                                            .filter(x -> x instanceof McpSchema.TextContent)
                                            .map(x -> ((McpSchema.TextContent) x).text()).reduce("", String::concat) : null;
                                    if (c == null) return Map.of("result", "ok");
                                    try { return OBJECT_MAPPER.readValue(c, Map.class); }
                                    catch (Exception e) { return Map.of("result", c); }
                                });
                            }
                        }).toList());
                    } catch (Exception e) {
                        lastFailureAt = System.currentTimeMillis();
                        log.warn("Stdio MCP [{}] 加载工具失败，本轮降级为空工具集（{} 秒内不再重试）| {}",
                                name, FAILURE_COOLDOWN_MS / 1000, e.getMessage());
                        return Flowable.empty();
                    }
                });
            }
            /**
             * 幂等关闭：热重装回收上一版工具集时调用。
             * <p>先摘掉 session 再关，重复调用不会二次关闭；关闭失败只告警，不阻断热重装
             * （stdio 子进程由 close 负责终止，异常时交给系统回收）。
             */
            @Override
            public void close() {
                McpSyncClient current = session;
                session = null;
                lastFailureAt = 0;
                if (current == null) {
                    return;
                }
                try {
                    current.close();
                    log.info("Stdio MCP [{}] session 已关闭", name);
                } catch (Exception e) {
                    log.warn("Stdio MCP [{}] session 关闭失败（忽略，继续回收）| {}", name, e.getMessage());
                }
            }

            @Override
            public String toString() {
                return "StdioMcpToolset[" + name + "]";
            }
        };
    }
}

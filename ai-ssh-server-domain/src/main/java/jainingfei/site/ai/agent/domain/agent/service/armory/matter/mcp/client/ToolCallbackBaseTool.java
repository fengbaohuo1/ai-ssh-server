package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Optional;

/**
 * Spring AI {@link ToolCallback} → ADK {@link BaseTool} 胶水适配器。
 * <p>
 * 将 Spring AI 注解驱动（{@code @Tool}）的工具包装为 Google ADK 可调用的工具。
 * 元数据来自 {@link ToolDefinition}，执行委托给 {@link ToolCallback#call(String)}。
 * <p>
 * V2: parametersJsonSchema 改用 ADK 原生的 {@link JsonSchema} 类型（对齐
 * {@link McpToolAdapter}），修复模型无法识别本地工具的问题。
 */
@Slf4j
public class ToolCallbackBaseTool extends BaseTool {

    private final ToolCallback toolCallback;
    private final ObjectMapper objectMapper;
    private final FunctionDeclaration functionDeclaration;

    public ToolCallbackBaseTool(ToolCallback toolCallback) {
        this(toolCallback, new ObjectMapper());
    }

    public ToolCallbackBaseTool(ToolCallback toolCallback, ObjectMapper objectMapper) {
        super(toolCallback.getToolDefinition().name(), toolCallback.getToolDefinition().description());
        this.toolCallback = toolCallback;
        this.objectMapper = objectMapper;

        ToolDefinition def = toolCallback.getToolDefinition();
        JsonSchema jsonSchema = buildJsonSchema(def.inputSchema());

        this.functionDeclaration = FunctionDeclaration.builder()
                .name(def.name())
                .description(def.description() != null ? def.description() : "")
                .parametersJsonSchema(jsonSchema)
                .build();
    }

    private JsonSchema buildJsonSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return objectMapper.convertValue(Map.of("type", "object"), JsonSchema.class);
        }
        try {
            Map<String, Object> schemaMap = objectMapper.readValue(schemaJson, Map.class);
            return objectMapper.convertValue(schemaMap, JsonSchema.class);
        } catch (Exception e) {
            log.warn("Failed to parse inputSchema JSON, using empty schema: {}", e.getMessage());
            return objectMapper.convertValue(Map.of("type", "object"), JsonSchema.class);
        }
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(functionDeclaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String inputJson = objectMapper.writeValueAsString(args);
            log.debug("Local tool [{}] called with args: {}", name(), inputJson);
            String resultJson = toolCallback.call(inputJson);
            log.debug("Local tool [{}] result: {}", name(), resultJson);
            if (resultJson == null) {
                return Map.of("result", (Object) "ok");
            }
            try {
                return objectMapper.readValue(resultJson, Map.class);
            } catch (Exception e) {
                return Map.of("result", (Object) resultJson);
            }
        });
    }

}

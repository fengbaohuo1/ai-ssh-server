package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.factory;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具创建工厂，按 ToolMcp 配置自动路由到对应策略，
 * 返回 {@link ToolCallback}[] 统一注入 {@code ToolCallingManager}。
 */
@Slf4j
@Component
public class McpCreateFactory {

    @Resource
    private List<ToolMcpCreateService> services;

    public ToolCallback[] create(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        for (ToolMcpCreateService service : services) {
            ToolCallback[] callbacks = service.createToolCallbacks(toolMcp);
            if (callbacks != null) {
                return callbacks;
            }
        }
        throw new RuntimeException("No ToolMcpCreateService matched for toolMcp config — " +
                "ensure one of remote/stdio/local is configured");
    }
}

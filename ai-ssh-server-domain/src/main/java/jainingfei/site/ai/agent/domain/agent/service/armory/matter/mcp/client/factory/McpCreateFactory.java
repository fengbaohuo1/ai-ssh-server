package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.factory;

import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具创建工厂，按 ToolMcp 配置自动路由到对应策略（remote/stdio/local），
 * 返回 ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}。
 */
@Slf4j
@Component
public class McpCreateFactory {

    @Resource
    private List<ToolMcpCreateService> services;

    public BaseToolset create(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        for (ToolMcpCreateService service : services) {
            BaseToolset toolset = service.createToolset(toolMcp);
            if (toolset != null) return toolset;
        }
        throw new RuntimeException("No ToolMcpCreateService matched — "
                + "ensure one of remote/stdio/local is configured");
    }
}

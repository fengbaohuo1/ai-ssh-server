package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolCallbackBaseTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Local MCP 工具创建策略：Spring 容器中的 {@link ToolCallbackProvider} Bean → ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}，
 * 通过 {@link ToolCallbackBaseTool} 桥接到 ADK。
 */
@Slf4j
@Service
public class LocalToolMcpCreateService implements ToolMcpCreateService {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public BaseToolset createToolset(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        if (local == null) return null;

        String beanName = local.getName();
        ToolCallbackProvider provider = (ToolCallbackProvider) applicationContext.getBean(beanName);
        ToolCallback[] callbacks = provider.getToolCallbacks();
        log.info("Local MCP [{}] → ToolCallbackProvider, count: {}", beanName, callbacks.length);

        List<BaseTool> tools = new ArrayList<>();
        for (ToolCallback cb : callbacks) tools.add(new ToolCallbackBaseTool(cb));
        return new StaticToolset(tools);
    }
}

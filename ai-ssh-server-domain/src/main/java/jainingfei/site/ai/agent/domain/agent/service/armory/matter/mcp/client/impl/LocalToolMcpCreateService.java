package jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 本地 MCP 工具创建策略：通过 YAML 的 {@code local.name} 定位容器中的
 * {@link ToolCallbackProvider} Bean，直接返回 {@link ToolCallback}[]。
 */
@Slf4j
@Service
public class LocalToolMcpCreateService implements ToolMcpCreateService {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public ToolCallback[] createToolCallbacks(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        if (local == null) {
            return null;
        }
        String beanName = local.getName();

        ToolCallbackProvider provider = (ToolCallbackProvider) applicationContext.getBean(beanName);
        log.info("tool local mcp initialize {}", beanName);

        return provider.getToolCallbacks();
    }
}

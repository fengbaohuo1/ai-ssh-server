package jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 静态工具集实现，将 {@link BaseTool} 列表包装为 ADK 可用的 {@link BaseToolset}。
 * <p>
 * 用于将 Spring AI {@link org.springframework.ai.tool.ToolCallback} 桥接过来的工具
 * 注册到 Google ADK 的 {@code LlmAgent.tools()} 中，使 ADK 能够原生管理工具调用循环。
 */
public class StaticToolset implements BaseToolset {

    private final List<BaseTool> tools;

    public StaticToolset(List<BaseTool> tools) {
        this.tools = tools;
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        return Flowable.fromIterable(tools);
    }

    @Override
    public void close() {
        // no resources to release
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 静态工具集，将 {@link BaseTool} 列表包装为 ADK {@link BaseToolset}。
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

package jainingfei.site.ai.agent.domain.agent.model.event;

/**
 * Agent 配置变更事件——配置页保存 {@code agent.yml} / {@code llm.yml} 后发布，
 * 由 app 层的装配监听器消费，触发运行期热重装（无需重启进程）。
 *
 * <p>为什么用事件而不是让 trigger 直接依赖 app 层：trigger 只依赖 api/domain，
 * 装配逻辑（{@code AiAgentAutoConfig} + {@code IArmoryService}）在 app/domain；
 * 通过 Spring 同步事件解耦，保存接口还能同步拿到重装结果回传前端。
 */
public class AgentConfigChangedEvent {

    /** 变更来源：model（llm.yml 覆盖层）/ agent-file（agent.yml）/ manual（手动 reload） */
    private final String source;

    public AgentConfigChangedEvent(String source) {
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}

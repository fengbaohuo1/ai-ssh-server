package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;

import com.google.adk.tools.ToolContext;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.AgentExecutionContext;

/**
 * 派发上下文提取器——三个派发工具共用同一段"从 ToolContext 抽取环境信息"的逻辑。
 *
 * <p>抽取的字段与来源：
 * <pre>
 *   userId             toolContext.userId()                       发起对话的用户
 *   appName            toolContext.invocationContext().appName()  配置表 app-name → AgentCatalog 精确查找
 *   agentName          toolContext.agentName()                    父智能体名（日志/追溯）
 *   parentSessionId    toolContext.invocationId()                  父会话 invocationId（仅追溯）
 *   terminalSessionId  SshSessionBindings.resolveTerminalSession()  父会话绑定的 SSH 连接（透传给子会话 state）
 * </pre>
 *
 * <p>toolContext 允许为 null（工具被直接调用、未经过完整 Runner 会话时），此时退化为 unknown，
 * 不抛异常——派发链路的可用性优先于上下文完备性。
 */
final class SubAgentDispatchContext {

    private SubAgentDispatchContext() {
    }

    /** 从工具上下文提取派发上下文（toolContext 为 null 时返回 unknown 兜底值） */
    static AgentExecutionContext from(ToolContext toolContext) {
        return AgentExecutionContext.builder()
                .userId(toolContext != null ? toolContext.userId() : "unknown")
                .appName(toolContext != null ? toolContext.invocationContext().appName() : "unknown")
                .agentName(toolContext != null ? toolContext.agentName() : "unknown")
                .parentSessionId(toolContext != null ? toolContext.invocationId() : "unknown")
                .terminalSessionId(SshSessionBindings.resolveTerminalSession(toolContext))
                .build();
    }
}

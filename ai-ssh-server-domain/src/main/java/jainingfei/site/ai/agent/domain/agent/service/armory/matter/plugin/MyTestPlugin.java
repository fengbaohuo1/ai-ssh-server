package jainingfei.site.ai.agent.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 可观测性插件：把"用户输入 / 智能体启动 / 模型请求 / 工具调用 / <b>子智能体派发</b>"打成一套可读日志。
 *
 * <p>子智能体派发单独识别并打 🧩 标记（教程 2-10）：多智能体链路下日志里会同时出现
 * 父智能体的推理、子智能体的多轮工具调用、派发本身，不做区分排障会非常痛苦。
 * 三个关键字段——{@code parentAgent}（谁派的）、{@code subAgent}（派给谁）、
 * {@code invocationId}（这次派发的唯一标识）——足以还原"一次用户请求触发了几次派发、结果如何"。
 *
 * <p>识别方式用<b>真实类型</b> {@link SubAgentDispatchTool}（教程用 {@code instanceof AgentTool}，
 * 而自定义派发工具继承的是 {@code BaseTool}，那个分支永远不会命中）；批量/规划派发的执行过程
 * 由编排器的任务级日志（DAG 轮次 / 任务状态）承接，观测各归其位。
 */
@Slf4j
@Service
public class MyTestPlugin extends BasePlugin {

    /** 日志中单字段最大长度（防超长 request/result 刷屏） */
    private static final int MAX_LOG_FIELD = 200;

    public MyTestPlugin(String name) {
        super(name);
    }

    public MyTestPlugin() {
        super("myTestPlugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        log.info("用户输入信息:{}", userMessage.text());
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        String agentName = agent.name();
        log.info("智能体名称:{}", agentName);
        return super.beforeAgentCallback(agent, callbackContext);
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        Optional<String> model = llmRequest.build().model();
        log.info("ai 模型:{}", model.orElse(""));
        return super.beforeModelCallback(callbackContext, llmRequest);
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(
            BaseTool tool, Map<String, Object> args, ToolContext toolContext) {
        if (tool instanceof SubAgentDispatchTool dispatchTool) {
            log.info("插件日志-🧩 子Agent派发开始 | parentAgent:{} | subAgent:{} | request:{} | invocationId:{}",
                    toolContext.agentName(), dispatchTool.subAgentName(),
                    truncate(args == null ? null : args.get("request"), MAX_LOG_FIELD),
                    toolContext.invocationId());
            return super.beforeToolCallback(tool, args, toolContext);
        }
        if ("executeCommand".equals(tool.name()) && (args == null || args.isEmpty() || !args.containsKey("command"))) {
            log.warn("[PLUGIN_TOOL] 拦截空参数 executeCommand，不调用实际工具");
            return Maybe.just(Map.of(
                    "success", false,
                    "command", "",
                    "output", "必须提供 command 参数"));
        }
        return super.beforeToolCallback(tool, args, toolContext);
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(
            BaseTool tool, Map<String, Object> args, ToolContext toolContext, Map<String, Object> result) {
        if (tool instanceof SubAgentDispatchTool dispatchTool) {
            boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
            log.info("插件日志-🧩 子Agent派发完成 | parentAgent:{} | subAgent:{} | success:{} | result:{} | invocationId:{}",
                    toolContext.agentName(), dispatchTool.subAgentName(), success,
                    truncate(result == null ? null : (success ? result.get("result") : result.get("error")), MAX_LOG_FIELD),
                    toolContext.invocationId());
        }
        return super.afterToolCallback(tool, args, toolContext, result);
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(
            BaseTool tool, Map<String, Object> args, ToolContext toolContext, Throwable error) {
        if (tool instanceof SubAgentDispatchTool dispatchTool) {
            log.error("插件日志-🧩 子Agent派发异常 | parentAgent:{} | subAgent:{} | invocationId:{} | error:{}",
                    toolContext.agentName(), dispatchTool.subAgentName(), toolContext.invocationId(),
                    error == null ? "unknown" : error.getMessage(), error);
        }
        return super.onToolErrorCallback(tool, args, toolContext, error);
    }

    /** 日志字段截断（null → "null"，超长保留头部 + 省略号） */
    private static String truncate(Object value, int max) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= max ? text : text.substring(0, max) + "...(+" + (text.length() - max) + ")";
    }
}

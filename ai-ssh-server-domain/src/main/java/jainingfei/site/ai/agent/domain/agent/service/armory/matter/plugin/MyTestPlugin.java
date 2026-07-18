package jainingfei.site.ai.agent.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Map;

@Slf4j
@Service
public class MyTestPlugin extends BasePlugin {

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
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        llmResponse.content().ifPresent(content ->
            content.parts().ifPresent(parts -> {
                int fcCount = 0, tcCount = 0, textCount = 0;
                for (var part : parts) {
                    if (part.functionCall().isPresent()) {
                        fcCount++;
                        var fc = part.functionCall().get();
                        log.warn("[PLUGIN_AFTER_MODEL] FC #{}/{}: name={} args={}",
                                fcCount, parts.size(), fc.name().orElse("?"), fc.args().orElse(null));
                    } else if (part.toolCall().isPresent()) {
                        tcCount++;
                        var tc = part.toolCall().get();
                        log.warn("[PLUGIN_AFTER_MODEL] ToolCall #{}/{}: name={} args={}",
                                tcCount, parts.size(), tc.name().orElse("?"), tc.args().orElse(null));
                    } else if (part.text().isPresent()) {
                        textCount++;
                        var t = part.text().get();
                        if (tcCount == 0 && fcCount == 0) {
                            log.info("[PLUGIN_AFTER_MODEL] Text: '{}'", t.length() > 80 ? t.substring(0, 80) + "..." : t);
                        }
                    }
                }
                if (fcCount > 0 || tcCount > 0) {
                    log.warn("[PLUGIN_AFTER_MODEL] Summary: {} parts ({} FC, {} TC, {} text) partial={} turnComplete={}",
                            parts.size(), fcCount, tcCount, textCount,
                            llmResponse.partial().orElse(false),
                            llmResponse.turnComplete().orElse(false));
                }
            }));
        return super.afterModelCallback(callbackContext, llmResponse);
    }

    @Override
    public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
        var fcs = event.functionCalls();
        if (!fcs.isEmpty()) {
            for (var fc : fcs) {
                log.warn("[PLUGIN_EVT] functionCall: name={} args={}", fc.name().orElse("?"), fc.args().orElse(null));
            }
        }
        return super.onEventCallback(invocationContext, event);
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(
            BaseTool tool, Map<String, Object> args, ToolContext toolContext) {
        if ("executeCommand".equals(tool.name()) && (args == null || args.isEmpty() || !args.containsKey("command"))) {
            log.warn("[PLUGIN_TOOL] 拦截空参数 executeCommand，不调用实际工具");
            return Maybe.just(Map.of(
                    "success", false,
                    "command", "",
                    "output", "必须提供 command 参数"));
        }
        return super.beforeToolCallback(tool, args, toolContext);
    }

}

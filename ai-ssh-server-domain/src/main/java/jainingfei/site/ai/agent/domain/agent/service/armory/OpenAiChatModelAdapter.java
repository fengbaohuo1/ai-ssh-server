package jainingfei.site.ai.agent.domain.agent.service.armory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAiChatModel 适配器：手动工具调用循环 + Agent 动作感知。
 * <p>
 * V2.1: 移除 fixOptions() 兼容胶水（ADK 1.6.0 修复了 Options 兼容）。
 */
@Slf4j
public class OpenAiChatModelAdapter implements ChatModel, StreamingChatModel {

    private static final int MAX_TOOL_ITERATIONS = 10;

    private final OpenAiChatModel delegate;
    private final OpenAiChatOptions cleanOptions;
    private final ToolCallingManager toolCallingManager;

    public OpenAiChatModelAdapter(OpenAiChatModel delegate,
                                  OpenAiChatOptions cleanOptions,
                                  ToolCallingManager toolCallingManager) {
        this.delegate = delegate;
        this.cleanOptions = cleanOptions;
        this.toolCallingManager = toolCallingManager;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return callWithToolLoop(prompt);
    }

    private ChatResponse callWithToolLoop(Prompt prompt) {
        Prompt currentPrompt = prompt;
        AgentStepBuffer buffer = AgentStepBuffer.getInstance();

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            buffer.append("{\"t\":\"thinking\"}");
            ChatResponse response = delegate.call(currentPrompt);

            if (!hasToolCalls(response)) {
                log.debug("Tool loop completed at iteration {}: text response", i);
                return response;
            }

            List<AssistantMessage.ToolCall> toolCalls = response.getResults().get(0).getOutput().getToolCalls();
            for (AssistantMessage.ToolCall tc : toolCalls) {
                log.info("MCP 工具调用: name={}, args={}", tc.name(), tc.arguments());
                String args = truncate(tc.arguments(), 200);
                buffer.append("{\"t\":\"tool_call\",\"n\":\""
                        + esc(tc.name()) + "\",\"a\":\"" + esc(args) + "\"}");
            }

            if (toolCallingManager == null) {
                log.warn("No ToolCallingManager configured — returning raw tool call response");
                return response;
            }

            ToolExecutionResult result = toolCallingManager.executeToolCalls(currentPrompt, response);
            List<Message> history = result.conversationHistory();
            logToolResults(history, buffer);
            log.info("Tool call result: historySize={}", history.size());

            currentPrompt = new Prompt(history, cleanOptions);
        }

        log.warn("Max tool iterations ({}) reached — forcing final call without tools", MAX_TOOL_ITERATIONS);
        return delegate.call(currentPrompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AgentStepBuffer buffer = AgentStepBuffer.getInstance();
        List<ChatResponse> allChunks = new ArrayList<>();

        buffer.append("{\"t\":\"thinking\"}");

        return delegate.stream(prompt)
                .doOnNext(allChunks::add)
                .filter(chunk -> !hasToolCalls(chunk))
                .concatWith(Flux.defer(() -> {
                    if (allChunks.isEmpty()) {
                        return Flux.empty();
                    }
                    ChatResponse lastChunk = allChunks.get(allChunks.size() - 1);
                    if (!hasToolCalls(lastChunk) || toolCallingManager == null) {
                        return Flux.empty();
                    }

                    List<AssistantMessage.ToolCall> toolCalls = lastChunk.getResults().get(0)
                            .getOutput().getToolCalls();
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        log.info("MCP 工具调用(stream): name={}, args={}", tc.name(), tc.arguments());
                        String args = truncate(tc.arguments(), 200);
                        buffer.append("{\"t\":\"tool_call\",\"n\":\""
                                + esc(tc.name()) + "\",\"a\":\"" + esc(args) + "\"}");
                    }

                    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, lastChunk);
                    List<Message> history = result.conversationHistory();
                    logToolResults(history, buffer);
                    log.info("Stream tool result: historySize={}", history.size());

                    return delegate.stream(new Prompt(history, cleanOptions));
                }));
    }

    private boolean hasToolCalls(ChatResponse response) {
        if (response == null) return false;
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) return false;
        AssistantMessage output = generations.get(0).getOutput();
        return output != null && output.hasToolCalls();
    }

    private void logToolResults(List<Message> history, AgentStepBuffer buffer) {
        for (Message msg : history) {
            if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    String preview = tr.responseData();
                    if (preview != null && preview.length() > 500) {
                        preview = preview.substring(0, 500) + "...";
                    }
                    log.info("MCP 工具结果: name={}, content={}", tr.name(), preview);
                    String p = preview != null ? preview : "";
                    buffer.append("{\"t\":\"tool_result\",\"n\":\""
                            + esc(tr.name()) + "\",\"r\":\"" + esc(p) + "\"}");
                }
            }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "").replace("\t", " ");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

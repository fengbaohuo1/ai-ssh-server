package jainingfei.site.ai.agent.domain.agent.service.armory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAiChatModel 适配器：修正 Prompt Options + 手动工具调用循环 + Agent 动作感知。
 * <p>
 * Spring AI 1.0 的 {@link OpenAiChatModel#call(Prompt)} 只做单次 API 调用，不自动完成
 * 工具调用循环（该循环由 {@code ChatClient} 高层 API 处理）。但 Google ADK 通过
 * {@code SpringAI} 适配器直接调用 {@link ChatModel}，所以必须在此适配器中手动管理
 * 工具循环。
 * <p>
 * 循环策略：
 * <ol>
 *   <li>首轮：使用带 {@code toolCallbacks} 的 Options — LLM 看到工具声明并可能调用</li>
 *   <li>工具执行：通过 {@link ToolCallingManager} 执行 LLM 返回的 function call</li>
 *   <li>次轮：使用无工具声明的 clean Options — 防止模型再次调用工具导致死循环</li>
 * </ol>
 * <p>
 * 步骤事件通过 {@link AgentStepBuffer} 推送到前端，实现 Agent 动作感知。
 */
@Slf4j
public class OpenAiChatModelAdapter implements ChatModel, StreamingChatModel {

    private static final int MAX_TOOL_ITERATIONS = 10;

    private final OpenAiChatModel delegate;
    private final OpenAiChatOptions defaultOptions;   // 带 toolCallbacks
    private final OpenAiChatOptions cleanOptions;      // 不带 toolCallbacks
    private final ToolCallingManager toolCallingManager;

    public OpenAiChatModelAdapter(OpenAiChatModel delegate,
                                  OpenAiChatOptions defaultOptions,
                                  OpenAiChatOptions cleanOptions,
                                  ToolCallingManager toolCallingManager) {
        this.delegate = delegate;
        this.defaultOptions = defaultOptions;
        this.cleanOptions = cleanOptions;
        this.toolCallingManager = toolCallingManager;
    }

    /**
     * 修正非 OpenAiChatOptions 的 Prompt Options。
     * ADK 在某些场景下生成 DefaultToolCallingChatOptions，需要替换为 OpenAiChatOptions。
     */
    private Prompt fixOptions(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof OpenAiChatOptions) {
            return prompt;
        }
        return new Prompt(prompt.getInstructions(), defaultOptions);
    }

    // ========== ChatModel (同步调用，含工具循环) ==========

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt fixedPrompt = fixOptions(prompt);
        return callWithToolLoop(fixedPrompt);
    }

    /**
     * 手动工具调用循环。
     * <p>
     * <b>核心策略</b>：首轮带工具声明，工具执行后换用 clean options（无工具声明），
     * 避免模型在收到 tool result 后继续调用工具导致死循环。
     */
    private ChatResponse callWithToolLoop(Prompt prompt) {
        Prompt currentPrompt = prompt;
        AgentStepBuffer buffer = AgentStepBuffer.getInstance();

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            buffer.append("{\"t\":\"thinking\"}");
            ChatResponse response = delegate.call(currentPrompt);

            if (!hasToolCalls(response)) {
                // 纯文本响应 → 完成
                log.debug("Tool loop completed at iteration {}: text response", i);
                return response;
            }

            // 有工具调用 → 记录详情 + 推送步骤事件
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

            // 关键：次轮使用 clean options（无 toolCallbacks），防止死循环
            currentPrompt = new Prompt(history, cleanOptions);
        }

        log.warn("Max tool iterations ({}) reached — forcing final call without tools", MAX_TOOL_ITERATIONS);
        return delegate.call(currentPrompt);
    }

    // ========== StreamingChatModel (SSE 流式，非阻塞工具循环) ==========

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt fixedPrompt = fixOptions(prompt);
        AgentStepBuffer buffer = AgentStepBuffer.getInstance();

        // 累积所有第一轮 chunk，供后续检测工具调用
        List<ChatResponse> allChunks = new ArrayList<>();

        buffer.append("{\"t\":\"thinking\"}");

        return delegate.stream(fixedPrompt)
                // 第一步：流式输出原 prompt（含工具声明），实时推送文本到前端
                .doOnNext(allChunks::add)
                .filter(chunk -> !hasToolCalls(chunk))   // 过滤工具调用chunk，不泄露给 ADK
                // 第二步：第一轮流结束后，检测工具调用并执行，再流式输出最终回复
                .concatWith(Flux.defer(() -> {
                    if (allChunks.isEmpty()) {
                        return Flux.empty();
                    }
                    ChatResponse lastChunk = allChunks.get(allChunks.size() - 1);
                    if (!hasToolCalls(lastChunk) || toolCallingManager == null) {
                        return Flux.empty();  // 无工具调用 → 第一轮已完整输出
                    }

                    // 推送工具调用步骤事件
                    List<AssistantMessage.ToolCall> toolCalls = lastChunk.getResults().get(0)
                            .getOutput().getToolCalls();
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        log.info("MCP 工具调用(stream): name={}, args={}", tc.name(), tc.arguments());
                        String args = truncate(tc.arguments(), 200);
                        buffer.append("{\"t\":\"tool_call\",\"n\":\""
                                + esc(tc.name()) + "\",\"a\":\"" + esc(args) + "\"}");
                    }

                    // 执行工具
                    ToolExecutionResult result = toolCallingManager.executeToolCalls(fixedPrompt, lastChunk);
                    List<Message> history = result.conversationHistory();
                    logToolResults(history, buffer);
                    log.info("Stream tool result: historySize={}", history.size());

                    // 用 clean options（无工具声明）流式输出最终回复，防死循环
                    return delegate.stream(new Prompt(history, cleanOptions));
                }));
    }

    // ========== helpers ==========

    private boolean hasToolCalls(ChatResponse response) {
        if (response == null) {
            return false;
        }
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) {
            return false;
        }
        AssistantMessage output = generations.get(0).getOutput();
        return output != null && output.hasToolCalls();
    }

    /**
     * 记录工具执行结果并推送步骤事件（截断超长内容至 500 字符）。
     */
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

    /** JSON 字符串转义 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ");
    }

    /** 截断字符串至 maxLen */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

}

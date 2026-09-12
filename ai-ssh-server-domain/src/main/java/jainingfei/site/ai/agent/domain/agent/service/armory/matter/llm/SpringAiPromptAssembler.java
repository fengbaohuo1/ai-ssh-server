package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.ToolConverter;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求侧转换——ADK 的 {@link LlmRequest} → Spring AI 的 {@link Prompt}。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>消息组装</b>：system 指令、user 内容（文本 + 函数响应）、历史 assistant 事件</li>
 *   <li><b>工具声明</b>：ADK 工具 → ToolConverter → Spring AI {@link ToolCallback}，
 *       再包成 per-call options（见 {@link #buildToolOptions}）</li>
 *   <li><b>历史配对规则</b>（两条 400 教训的沉淀）：无草稿且无工具调用的纯文本 assistant 事件跳过；
 *       带 FunctionCall 的事件必须保留——否则 tool 消息找不到前置 tool_calls</li>
 * </ol>
 */
@Slf4j
public class SpringAiPromptAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ReasoningDraftProtocol draftProtocol;
    /** 通道的草稿载体（null = 通用元数据约定） */
    private final ReasoningDraftAccess draftAccess;

    public SpringAiPromptAssembler(ChatModel chatModel, ReasoningDraftProtocol draftProtocol,
                                   ReasoningDraftAccess draftAccess) {
        this.chatModel = chatModel;
        this.draftProtocol = draftProtocol;
        this.draftAccess = draftAccess;
    }

    /** ADK 请求 → Spring AI Prompt（含 system/user/历史消息与工具 options） */
    public Prompt toPrompt(LlmRequest request) {
        List<Message> messages = new ArrayList<>();
        List<String> systemTexts = new ArrayList<>(request.getSystemInstructions());

        for (Content content : request.contents()) {
            String role = content.role().orElse("user").toLowerCase();
            switch (role) {
                case "system":
                    content.parts().ifPresent(parts -> {
                        StringBuilder sb = new StringBuilder();
                        for (Part part : parts) part.text().ifPresent(sb::append);
                        if (sb.length() > 0) systemTexts.add(sb.toString());
                    });
                    break;
                case "user":
                    messages.addAll(toUserMessages(content));
                    break;
                case "model":
                case "assistant":
                    // 思考模型：历史 assistant 需要携带推理内容回传；载体与解析规则见 ReasoningDraftProtocol
                    messages.addAll(draftProtocol.toHistoryMessages(content, draftAccess));
                    break;
                default:
                    messages.addAll(toUserMessages(content));
            }
        }

        if (!systemTexts.isEmpty()) {
            messages.add(0, new SystemMessage(String.join("\n\n", systemTexts)));
        }

        return new Prompt(messages, buildToolOptions(request));
    }

    /** user 内容 → 消息列表：文本 → UserMessage；函数响应 → ToolResponseMessage（与 assistant tool_calls 配对） */
    private List<Message> toUserMessages(Content content) {
        List<Message> list = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                text.append(part.text().get());
            } else if (part.functionResponse().isPresent()) {
                FunctionResponse fr = part.functionResponse().get();
                String data = fr.response().map(response -> {
                    try { return MAPPER.writeValueAsString(response); } catch (Exception e) { return "{}"; }
                }).orElse("{}");
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        fr.id().orElse(""), fr.name().orElse(""), data));
            }
        }

        String plainText = text.toString();
        if (!plainText.isEmpty() || toolResponses.isEmpty()) {
            list.add(new UserMessage(plainText));
        }
        if (!toolResponses.isEmpty()) {
            list.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        return list;
    }

    /**
     * 构造工具轮次的 per-call options —— <b>厂商无关</b>，与 ADK 官方适配器同一套写法。
     *
     * <p>要绕的洞：provider 的 ChatModel 会把 {@code Prompt.getOptions()} 强转成自己的 options
     * 子类（OpenAiChatModel / DeepSeekChatModel 的 createRequest 都是
     * {@code (XxxChatOptions) prompt.getOptions()}），塞通用 {@code DefaultToolCallingChatOptions}
     * 会直接 ClassCastException；而且 options 非空时 provider 不再合并默认值。
     *
     * <p>通用解法：从 {@link ChatModel#getOptions()} 取出模型自己的默认 options，
     * 用 {@link ToolCallingChatOptions#mutate()} 复制（保留具体子类与全部默认参数）再挂本轮工具回调。
     *
     * <p>官方同源实现：{@code google-adk-spring-ai} 的 {@code MessageConverter#buildChatOptions}。
     */
    private ChatOptions buildToolOptions(LlmRequest request) {
        if (request.tools() == null || request.tools().isEmpty()) {
            return null;
        }
        List<ToolCallback> callbacks = new ToolConverter().convertToSpringAiTools(request.tools());
        if (callbacks.isEmpty()) {
            return null;
        }
        if (chatModel.getOptions() instanceof ToolCallingChatOptions toolCapable) {
            return toolCapable.mutate().toolCallbacks(callbacks).build();
        }
        // 兜底：该 provider 未暴露可携带工具的默认 options（只有这类 provider 才吃得下通用 options）
        return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    }
}

package jainingfei.site.ai.agent.test.agent;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.stream.Collectors;

/**
 * Spring AI 1.0 {@link ChatModel} → Google ADK {@link BaseLlm} 适配器。
 *
 * <p>google-adk-spring-ai:1.4.0 官方适配器不兼容 Spring AI 1.0：
 * 其内部 Prompt 未携带 OpenAiChatOptions，导致 createRequest 时 ClassCastException。
 * 此处显式将 defaultOptions 注入每个 Prompt 解决。
 */
public class SpringAiAdkModel extends BaseLlm {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAdkModel.class);

    private final ChatModel chatModel;
    private final OpenAiChatOptions defaultOptions;

    public SpringAiAdkModel(ChatModel chatModel, OpenAiChatOptions defaultOptions) {
        super(defaultOptions.getModel());
        this.chatModel = chatModel;
        this.defaultOptions = defaultOptions;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        String promptText = llmRequest.contents().stream()
                .map(Content::text)
                .collect(Collectors.joining("\n"));

        log.debug("SpringAiAdkModel prompt: {}", promptText);

        // 关键：Prompt 必须携带 OpenAiChatOptions，否则 OpenAiChatModel.createRequest 转型失败
        Prompt prompt = new Prompt(promptText, defaultOptions);
        ChatResponse chatResponse = chatModel.call(prompt);

        String responseText = chatResponse.getResults().stream()
                .map(gen -> gen.getOutput())
                .map(Message::getText)
                .collect(Collectors.joining("\n"));

        log.debug("SpringAiAdkModel response: {}", responseText);

        LlmResponse llmResponse = LlmResponse.builder()
                .content(Content.builder()
                        .role("assistant")
                        .parts(Part.fromText(responseText))
                        .build())
                .build();

        return Flowable.just(llmResponse);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Live connection not supported by SpringAiAdkModel");
    }
}

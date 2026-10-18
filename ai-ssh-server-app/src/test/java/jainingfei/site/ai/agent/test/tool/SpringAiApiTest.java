package jainingfei.site.ai.agent.test.tool;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Objects;

@Slf4j
public class SpringAiApiTest {

    @Test
    public void test() {
        // Spring AI 1.0: OpenAiApi 已被移除，所有配置通过 OpenAiChatOptions 完成
        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl("https://api.deepseek.com")
                        .apiKey("sk-test")
                        .model("deepseek-v4-flash")
                        .build())
                .build();

        // Spring AI 1.0: call() 需要传入 Prompt 对象，返回 ChatResponse
        ChatResponse response = chatModel.call(new Prompt("你好!"));

        log.info("测试结果:{}", Objects.requireNonNull(response.getResult()).getOutput().getText());
    }
}

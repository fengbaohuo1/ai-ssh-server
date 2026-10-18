package jainingfei.site.ai.agent.test.tool;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
public class LangChain4jApiTest {

    @Test
    public void test() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey("sk-test")
                .modelName("deepseek-v4-flash")
                .build();

        String chatRes = model.chat("你好！");
        log.info("chatMsg: {}", chatRes);
    }
}

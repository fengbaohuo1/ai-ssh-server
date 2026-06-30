package jainingfei.site.ai.agent.test.agent;


import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;


import java.util.List;

@Slf4j
@SpringBootTest
public class ChatServiceTest {

    @Resource
    private IChatService chatService;


    @Test
    public void test_handleMessage_01() {
        List<String> message = chatService.handleMessage("100003", "贾宁飞", "你具备哪些能力");
        log.info("测试结果:{}", JSON.toJSONString(message));
    }

    @Test
    public void test_handleMessage_02() {
        List<String> message = chatService.handleMessage("100003", "贾宁飞", "把jianingfei转成大写");
        log.info("测试结果:{}", JSON.toJSONString(message));
    }

    @Test
    public void test_handleMessage_03() {
        List<String> message = chatService.handleMessage("100003", "贾宁飞", "制定一个学习计划");
        log.info("测试结果:{}", JSON.toJSONString(message));
    }
}

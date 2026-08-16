package jainingfei.site.ai.agent.test.app;


import com.alibaba.fastjson.JSON;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@SpringBootTest
public class AiAgentAutoConfigTest {

    @Resource
    private ApplicationContext applicationContext;

    @Test
    public void test_agent() throws InterruptedException {
        AiAgentRegisterVO aiAgentRegisterVO = applicationContext.getBean("100001", AiAgentRegisterVO.class);

        Runner runner = aiAgentRegisterVO.getRunner();
        String appName = aiAgentRegisterVO.getAppName();
        String userId = "jianingfei";

        // 用 runner.sessionService() 创建 session 并获取 ID
        String sessionId = runner.sessionService()
                .createSession(appName, userId)
                .map(Session::id)
                .blockingGet();

        log.info("Session created: {}", sessionId);

        Content userMsg = Content.fromParts(Part.fromText("编写冒泡排序"));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> {
            log.info(event.stringifyContent());
            outputs.add(event.stringifyContent());
        });

        log.info("测试结果:{}", JSON.toJSONString(outputs));
    }

    @Test
    public void test_handlerMessage_02() {
        AiAgentRegisterVO aiAgentRegisterVO = applicationContext.getBean("100002", AiAgentRegisterVO.class);

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService()
                .createSession(appName, "jianingfei")
                .blockingGet();

        Content userMsg = Content.fromParts(Part.fromText("你具备哪些能力"));
        Flowable<Event> events = runner.runAsync("jianingfei", session.id(), userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> {
            log.info(event.stringifyContent());
            outputs.add(event.stringifyContent());
        });

        log.info("测试结果:{}", JSON.toJSONString(outputs));
    }

    @Test
    public void test_handlerMessage_03() {
        AiAgentRegisterVO aiAgentRegisterVO = applicationContext.getBean("100003", AiAgentRegisterVO.class);

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService()
                .createSession(appName, "jianingfei")
                .blockingGet();

        Content userMsg = Content.fromParts(Part.fromText("把jianingfei转换为大写"));
        Flowable<Event> events = runner.runAsync("jianingfei", session.id(), userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> {
            log.info(event.stringifyContent());
            outputs.add(event.stringifyContent());
        });

        log.info("测试结果:{}", JSON.toJSONString(outputs));
    }

}

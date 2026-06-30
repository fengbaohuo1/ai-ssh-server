package jainingfei.site.ai.agent.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
public class ChatService implements IChatService {
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();
    /** sessionId → 对话历史（最近 N 轮），线程安全 */
    private final Map<String, Deque<Exchange>> conversationHistories = new ConcurrentHashMap<>();
    /** 每个会话最多保留的对话轮数 */
    private static final int MAX_HISTORY_TURNS = 20;

    /** 一轮对话交换：用户消息 + 助手回复 */
    private record Exchange(String userMessage, String assistantMessage) {
    }

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        List<AiAgentConfigTableVO.Agent> agents = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(tables)) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (vo.getAgent() != null) {
                    agents.add(vo.getAgent());
                }
            }
        }
        return agents;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        String sessionId = userSessions.computeIfAbsent(userId, uid -> {
            Session session = runner.sessionService().createSession(appName, uid)
                    .blockingGet();
            return session.id();
        });
        // 新 session 初始化空历史（computeIfAbsent 保证只执行一次）
        conversationHistories.putIfAbsent(sessionId, new ConcurrentLinkedDeque<>());
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(aiAgentRegisterVO.getAgentId(), userId);
        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        sessionId = ensureSessionExists(runner, aiAgentRegisterVO.getAppName(), userId, sessionId);

        // 将对话历史嵌入当前消息文本，确保 LLM 看到完整上下文
        String contextualizedMessage = buildContextualizedMessage(sessionId, message);
        Content userMsg = Content.fromParts(Part.fromText(contextualizedMessage));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        // 本轮对话完成后，追加到历史
        String assistantResponse = String.join("\n", outputs);
        appendToHistory(sessionId, message, assistantResponse);

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String effectiveSessionId = ensureSessionExists(runner, aiAgentRegisterVO.getAppName(), userId, sessionId);

        // 将对话历史嵌入当前消息文本，确保 LLM 看到完整上下文
        String contextualizedMessage = buildContextualizedMessage(effectiveSessionId, message);

        // 捕获流式响应文本，用于完成后追加历史
        StringBuilder responseCapture = new StringBuilder();
        Content userMsg = Content.fromParts(Part.fromText(contextualizedMessage));
        return runner.runAsync(userId, effectiveSessionId, userMsg,
                        RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build())
                .doOnNext(event -> {
                    String content = event.stringifyContent();
                    if (content != null && !content.isEmpty()) {
                        responseCapture.append(content);
                    }
                })
                .doOnComplete(() -> {
                    appendToHistory(effectiveSessionId, message, responseCapture.toString());
                });
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = ensureSessionExists(aiAgentRegisterVO.getRunner(), aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());

        List<Part> parts = new ArrayList<>();
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (ObjectUtils.isNotEmpty(texts)) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (ObjectUtils.isNotEmpty(files)) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (ObjectUtils.isNotEmpty(inlineDatas)) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), sessionId, content,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        return outputs;
    }

    /**
     * 校验 session 是否仍然存在（重启后 InMemorySessionService 会清空），
     * 如果已失效则自动创建新 session 并更新缓存。
     */
    private String ensureSessionExists(InMemoryRunner runner, String appName, String userId, String sessionId) {
        Session session = runner.sessionService().getSession(appName, userId, sessionId,
                        java.util.Optional.empty()).blockingGet();
        if (session == null) {
            log.info("会话已失效，自动创建新会话 appName:{} userId:{} oldSessionId:{}", appName, userId, sessionId);
            Session newSession = runner.sessionService().createSession(appName, userId).blockingGet();
            userSessions.put(userId, newSession.id());
            return newSession.id();
        }
        return sessionId;
    }

    @Override
    public void clearHistory(String sessionId) {
        Deque<Exchange> removed = conversationHistories.remove(sessionId);
        if (removed != null) {
            log.info("已清理会话历史 sessionId:{} turns:{}", sessionId, removed.size());
        }
    }

    /**
     * 将对话历史以文本格式嵌入当前消息，使 LLM 感知完整上下文。
     * 无历史时返回原始消息不变。
     */
    private String buildContextualizedMessage(String sessionId, String currentMessage) {
        Deque<Exchange> history = conversationHistories.get(sessionId);
        if (history == null || history.isEmpty()) {
            log.info("对话上下文 sessionId:{} 无历史记录", sessionId);
            return currentMessage;
        }
        log.info("对话上下文 sessionId:{} 注入 {} 轮历史", sessionId, history.size());
        StringBuilder sb = new StringBuilder();
        sb.append("<conversation_history>\n");
        for (Exchange exchange : history) {
            sb.append("user: ").append(exchange.userMessage()).append("\n");
            sb.append("assistant: ").append(exchange.assistantMessage()).append("\n");
        }
        sb.append("</conversation_history>\n\n");
        sb.append(currentMessage);
        return sb.toString();
    }

    /**
     * 追加一轮对话到历史，超出上限时移除最旧的记录。
     */
    private void appendToHistory(String sessionId, String userMessage, String assistantMessage) {
        Deque<Exchange> history = conversationHistories.computeIfAbsent(sessionId,
                k -> new ConcurrentLinkedDeque<>());
        synchronized (history) {
            while (history.size() >= MAX_HISTORY_TURNS) {
                history.pollFirst();
            }
            history.addLast(new Exchange(userMessage, assistantMessage));
        }
        log.info("对话历史追加 sessionId:{} userMsg:{} asstMsg:{} total:{}",
                sessionId, userMessage.substring(0, Math.min(50, userMessage.length())),
                assistantMessage.substring(0, Math.min(50, assistantMessage.length())),
                history.size());
    }
}

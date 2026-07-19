package jainingfei.site.ai.agent.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Optional;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private ISshConnectionService sshConnectionService;

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

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

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

        // ADK 的 InMemorySessionService 自动管理对话历史
        Content userMsg = Content.fromParts(Part.fromText(message));
        return runner.runAsync(userId, effectiveSessionId, userMsg,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String effectiveSessionId = ensureSessionExists(runner, aiAgentRegisterVO.getAppName(), userId, sessionId);

        // 将 SSH 连接 ID 设置到静态字段，供 SshExecuteAdkTool 在 ADK 线程中读取
        // terminalSessionId 字段携带的是 connectionId（统一终端会话后，以 connectionId 为 key）
        String serverContext = "";
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            log.info("设置 SSH 连接 ID: {}", terminalSessionId);
            SshExecuteAdkTool.bindSession(effectiveSessionId, terminalSessionId);
            // 查询服务器信息，注入到 LLM 上下文，让它知道连了哪台机器
            try {
                SshConnectionAggregate aggregate = sshConnectionService.queryConnectionDetail(terminalSessionId);
                if (aggregate != null && aggregate.getConnection() != null) {
                    var conn = aggregate.getConnection();
                    serverContext = String.format(
                            "\n<server_context>\n当前连接的服务器: %s@%s:%d\n服务器名称: %s\n</server_context>\n\n",
                            conn.getUsername(), conn.getHost(), conn.getPort(), conn.getConnectionName());
                    log.info("已注入服务器上下文: {}", serverContext.trim());
                }
            } catch (Exception e) {
                log.warn("获取服务器信息失败 connectionId={}", terminalSessionId, e);
            }
        }

        // ADK 的 InMemorySessionService 自动管理对话历史，不需要手动嵌入
        // 只在首次连接时注入服务器上下文
        String contextualizedMessage = serverContext.isEmpty() ? message : serverContext + message;
        Content userMsg = Content.fromParts(Part.fromText(contextualizedMessage));
        return runner.runAsync(userId, effectiveSessionId, userMsg,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
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
        // ADK InMemorySessionService 管理历史，无需手动清理
    }
}

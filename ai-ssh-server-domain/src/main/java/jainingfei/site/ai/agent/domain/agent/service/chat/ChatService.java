package jainingfei.site.ai.agent.domain.agent.service.chat;
import jainingfei.site.ai.agent.domain.agent.support.SshSessionBindings;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Optional;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
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

    /** 冷启动"重建空会话 + 历史回顾注入"的 sessionId 粒度锁（防并发首请求重复回放） */
    private final Map<String, Object> sessionRestoreLocks = new ConcurrentHashMap<>();

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private ISshConnectionService sshConnectionService;
    @Resource
    private IChatHistoryRepository chatHistoryRepository;
    @Resource
    private SessionHistoryReplayer sessionHistoryReplayer;

    @Override
    public List<AiAgentConfigTableVO> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (ObjectUtils.isEmpty(tables)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(tables.values());
    }

    @Override
    public String createSession(String agentId, String userId) {
        return createSession(agentId, userId, null);
    }

    @Override
    public String createSession(String agentId, String userId, String clientSessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        String sessionId;
        if (clientSessionId != null && !clientSessionId.isBlank()) {
            // 会话连续性（2-8 修复③）：客户端带回旧 sessionId——
            // 进程内已有则复用；进程内没有（服务重启）则以该 id 重建 ADK 会话，
            // 并注入 DB 历史回顾（P0 冷启动一致性），配合 RootNode 的 DB 冷启动恢复
            sessionId = userSessions.computeIfAbsent(userId, uid -> clientSessionId);
            Session existSession = runner.sessionService()
                    .getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
            if (existSession == null) {
                // 并发首请求可能同时看到"空快照"：按 sessionId 加锁，重建+回放只执行一次
                Object lock = sessionRestoreLocks.computeIfAbsent(sessionId, k -> new Object());
                synchronized (lock) {
                    Session recheck = runner.sessionService()
                            .getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
                    if (recheck == null) {
                        runner.sessionService()
                                .createSession(appName, userId, new ConcurrentHashMap<>(), sessionId)
                                .blockingGet();
                        log.info("以客户端 sessionId 重建 ADK 会话 userId={} sessionId={}", userId, sessionId);
                        sessionHistoryReplayer.replayIntoSession(runner, appName, userId, sessionId);
                    }
                }
                sessionRestoreLocks.remove(sessionId);
            }
        } else {
            sessionId = userSessions.computeIfAbsent(userId, uid -> {
                Session session = runner.sessionService().createSession(appName, uid)
                        .blockingGet();
                return session.id();
            });
        }

        // 会话元数据旁路落库（2-8）：saveSession 按 id 幂等，重启重复创建不报错
        try {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title("新会话")
                    .messageCount(0)
                    .build());
        } catch (Exception e) {
            log.error("保存会话元数据失败 sessionId={}", sessionId, e);
        }
        return sessionId;
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        return chatHistoryRepository.querySessionList(agentId, userId, limit > 0 ? limit : 20);
    }

    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return chatHistoryRepository.queryMessageList(sessionId, limit > 0 ? limit : 100);
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

        Runner runner = aiAgentRegisterVO.getRunner();
        sessionId = ensureSessionExists(runner, aiAgentRegisterVO.getAppName(), userId, sessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
        // V3 partial/聚合语义：只收集分片增量文本（聚合事件与分片重复时跳过），
        // 同步对话返回拼接后的可见文本（与旧版单轮文本一致；重复内容不会累加）
        List<String> outputs = new ArrayList<>();
        StringBuilder accumulated = new StringBuilder();
        events.blockingForEach(event -> {
            String delta = EventTextUtil.visibleTextDelta(event, accumulated);
            if (delta != null) {
                outputs.add(delta);
            }
        });

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();
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

        Runner runner = aiAgentRegisterVO.getRunner();
        String effectiveSessionId = ensureSessionExists(runner, aiAgentRegisterVO.getAppName(), userId, sessionId);

        // 将 SSH 连接 ID 设置到静态字段，供 SshExecuteAdkTool 在 ADK 线程中读取
        // terminalSessionId 字段携带的是 connectionId（统一终端会话后，以 connectionId 为 key）
        String serverContext = "";
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            log.info("设置 SSH 连接 ID: {}", terminalSessionId);
            SshSessionBindings.bindSession(effectiveSessionId, terminalSessionId);
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

        Runner runner = aiAgentRegisterVO.getRunner();
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), sessionId, content,
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());
        List<String> outputs = new ArrayList<>();
        StringBuilder accumulated = new StringBuilder();
        events.blockingForEach(event -> {
            String delta = EventTextUtil.visibleTextDelta(event, accumulated);
            if (delta != null) {
                outputs.add(delta);
            }
        });
        return outputs;
    }

    /**
     * 校验 session 是否仍然存在（重启后 InMemorySessionService 会清空），
     * 如果已失效则自动创建新 session 并更新缓存。
     */
    private String ensureSessionExists(Runner runner, String appName, String userId, String sessionId) {
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

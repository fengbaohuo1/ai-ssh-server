package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct Root Node（根节点）。
 *
 * <p>职责：
 * <ol>
 *   <li>从 ChatRequestDTO 提取会话参数</li>
 *   <li>通过 ChatService 校验/创建 session（与旧链路同一 runner，保证 ADK 会话可见；
 *       支持客户端带回旧 sessionId，重启后冷启动恢复）</li>
 *   <li>从 DB 加载最近历史消息，恢复跨请求的对话上下文（2-8 冷启动）</li>
 *   <li>初始化 DynamicContext</li>
 *   <li>绑定终端会话 ID（ThreadLocal）</li>
 *   <li>追加用户消息到历史</li>
 *   <li>路由到 AiCallNode</li>
 * </ol>
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    /** 冷启动恢复加载的历史消息条数 */
    private static final int COLD_START_HISTORY_LIMIT = 50;

    @Resource
    private IChatService chatService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        // 2. 通过 ChatService 创建/恢复 session（2-8：透传客户端 sessionId，重启后旧会话可接续）
        String sessionId = chatService.createSession(agentId, userId, requestParameter.getSessionId());
        ctx.setSessionId(sessionId);

        // 3. 绑定终端会话（ThreadLocal + ConcurrentHashMap 双重保障）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            setCurrentTerminalSession(terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
        } else {
            // 回退：从会话映射中获取
            String bound = getTerminalSession(sessionId);
            if (bound != null) {
                setCurrentTerminalSession(bound);
            }
        }

        // 4. 初始化上下文
        ctx.setUserId(userId);
        ctx.setAgentId(agentId);
        ctx.setTerminalSessionId(terminalSessionId);

        // 4.1 记录原始用户任务（防长对话任务漂移，TaskProvider 的数据源之一）
        ctx.setOriginalUserTask(message);

        // 4.2 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        ctx.setResult(result);

        // 4.3 冷启动恢复（2-8）：从 DB 加载最近 50 条历史消息，恢复对话上下文。
        // 业务侧 messageHistory 每次请求都是全新的，跨请求（尤其跨重启）的历史
        // 只能从 chat_message 兜底——TaskProvider 的任务锚点、LongTermMemoryProvider
        // 的召回查询都依赖它。旁路语义：加载失败从空历史开始，不阻断主流程。
        try {
            List<ChatMessageEntity> recentMessages = longTermMemoryService.getRecentMessages(sessionId, COLD_START_HISTORY_LIMIT);
            for (ChatMessageEntity msg : recentMessages) {
                Map<String, Object> map = new HashMap<>();
                map.put("role", msg.getRole());
                map.put("content", msg.getContent() != null ? msg.getContent() : "");
                // tool 消息补全 tool_call_id 和 name，保持与 appendToolMessage 的结构一致
                if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                    map.put("tool_call_id", msg.getToolCallId());
                    map.put("name", msg.getToolName());
                }
                ctx.appendMessage(map);
            }
            if (!recentMessages.isEmpty()) {
                log.info("冷启动恢复: 加载 {} 条历史消息 sessionId={}", recentMessages.size(), sessionId);
            }
        } catch (Exception e) {
            log.warn("冷启动恢复失败，从空历史开始 sessionId={}", sessionId, e);
        }

        // 5. 追加用户消息到历史
        ctx.appendUserMessage(message);

        log.info("ReAct RootNode - 初始化完成 sessionId={} userId={} agentId={} terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 6. 路由到 AI 调用节点
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }

}

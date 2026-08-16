package jainingfei.site.ai.agent.cases.react.node;

import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct Root Node（根节点）。
 *
 * <p>职责：
 * <ol>
 *   <li>从 ChatRequestDTO 提取会话参数</li>
 *   <li>通过 ChatService 校验/创建 session（与旧链路同一 runner，保证 ADK 会话可见）</li>
 *   <li>初始化 DynamicContext</li>
 *   <li>绑定终端会话 ID（ThreadLocal）</li>
 *   <li>追加用户消息到历史</li>
 *   <li>路由到 AiCallNode</li>
 * </ol>
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatService chatService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext ctx) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        // 2. 通过 ChatService 创建/获取 session（与旧链路使用同一 runner 实例）
        String sessionId = chatService.createSession(agentId, userId);
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

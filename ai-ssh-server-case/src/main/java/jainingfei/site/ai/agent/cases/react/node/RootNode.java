package jainingfei.site.ai.agent.cases.react.node;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.cases.react.AbstractAIAgentReActSupport;
import jainingfei.site.ai.agent.cases.react.factory.DefaultReActFactory;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct Root Node（根节点）——回合前置初始化。
 *
 * <p>职责（V3 托管循环：一次用户消息 = 一个回合）：
 * <ol>
 *   <li>从 ChatRequestDTO 提取会话参数</li>
 *   <li>通过 ChatService 创建/恢复 ADK 会话（透传客户端 sessionId，重启后冷启动接续）</li>
 *   <li>绑定 SSH 终端会话（SshExecuteAdkTool 静态映射 + 会话级映射双写；请求未带时从绑定恢复）</li>
 *   <li>从 DB 加载最近历史消息，恢复业务侧 messageHistory（供 ContextProvider 消费）</li>
 *   <li>初始化 DynamicContext 回合状态 + 结果 DTO，追加用户消息</li>
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
        log.info("ReAct RootNode - 回合前置初始化");

        // 1. 提取会话参数
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();
        if (message == null) {
            message = "";
        }

        // 2. 通过 ChatService 创建/恢复 ADK 会话（2-8：透传客户端 sessionId，重启后旧会话可接续）
        String sessionId = chatService.createSession(agentId, userId, requestParameter.getSessionId());

        // 3. 绑定终端会话（工具执行靠 SshExecuteAdkTool 静态映射；会话级映射支持跨请求回退）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.bindSession(sessionId, terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
            setCurrentTerminalSession(terminalSessionId);
        } else {
            String bound = getTerminalSession(sessionId);
            if (bound != null) {
                SshExecuteAdkTool.bindSession(sessionId, bound);
                setCurrentTerminalSession(bound);
                terminalSessionId = bound;
            }
        }

        // 4. 初始化上下文
        ctx.setSessionId(sessionId);
        ctx.setUserId(userId);
        ctx.setAgentId(agentId);
        ctx.setTerminalSessionId(terminalSessionId);
        ctx.setOriginalUserTask(message);

        // 5. 冷启动恢复（2-8）：从 DB 加载最近 50 条历史消息恢复 messageHistory——
        //    业务侧历史每次请求全新，跨请求/跨重启只能从 chat_message 兜底
        //    （TaskProvider 任务锚点、工具摘要、LongTermMemoryProvider 召回都依赖它）
        try {
            List<ChatMessageEntity> recentMessages =
                    longTermMemoryService.getRecentMessages(sessionId, COLD_START_HISTORY_LIMIT);
            List<Map<String, Object>> history = new ArrayList<>();
            for (ChatMessageEntity msg : recentMessages) {
                Map<String, Object> map = new HashMap<>();
                map.put("role", msg.getRole());
                map.put("content", msg.getContent() != null ? msg.getContent() : "");
                // tool 消息补全 tool_call_id 和 name，保持与 appendToolMessage 的结构一致
                if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                    map.put("tool_call_id", msg.getToolCallId());
                    map.put("name", msg.getToolName());
                }
                history.add(map);
            }
            ctx.setMessageHistory(history);
            if (!recentMessages.isEmpty()) {
                log.info("冷启动恢复: 加载 {} 条历史消息 sessionId={}", recentMessages.size(), sessionId);
            }
        } catch (Exception e) {
            log.warn("冷启动恢复失败，从空历史开始 sessionId={}", sessionId, e);
        }

        // 6. 追加用户消息到历史
        ctx.appendUserMessage(message);

        // 7. 初始化结果 DTO
        ctx.setResult(ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build());

        log.info("ReAct RootNode - 初始化完成 sessionId={} userId={} agentId={} terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 8. 路由到 AI 调用节点
        return router(requestParameter, ctx);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }

}

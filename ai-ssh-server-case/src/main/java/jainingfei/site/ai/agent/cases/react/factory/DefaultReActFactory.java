package jainingfei.site.ai.agent.cases.react.factory;

import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import jainingfei.site.ai.agent.domain.agent.support.EventTextUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工厂 + 动态上下文。
 *
 * <p>DynamicContext 是贯穿整个 ReAct 节点链路的回合级状态容器，保存：
 * <ul>
 *   <li>会话信息（sessionId, userId, agentId, terminalSessionId）</li>
 *   <li>消息历史（messageHistory，业务侧；供 ContextProvider 消费）</li>
 *   <li>回合执行收集物（V3 托管循环：一次用户消息 = 一次 runAsync）：
 *       可见文本累积 / 逐 LLM 调用的助手文本段 / FunctionResponse 结果集 / 工具调用计数</li>
 *   <li>终止状态（stopReason, errorMessage）与结果 DTO</li>
 * </ul>
 *
 * <p>节点链路（V3 回合流水线，无循环回跳——循环由 ADK Runner 托管）：
 * RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 */
public class DefaultReActFactory {

    /**
     * ReAct 回合运行时动态上下文。
     * 继承 wrench 框架的 DynamicContext 以兼容 {@code AbstractMultiThreadStrategyRouter} 泛型约束。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class DynamicContext extends jianingfei.site.wrench.design.framework.tree.DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  会话基本信息
        // ══════════════════════════════════════════════════════════

        /** 对话会话 ID */
        private String sessionId;

        /** 用户 ID */
        private String userId;

        /** 智能体 ID */
        private String agentId;

        /** SSH 终端会话 ID（即 connectionId） */
        private String terminalSessionId;

        /** SSE 事件发射器（同步 chat 传内部 emitter） */
        private SseEmitter emitter;

        /** 原始用户任务（首条用户消息原文）——防长对话任务漂移，TaskProvider 的数据源之一 */
        private String originalUserTask;

        // ══════════════════════════════════════════════════════════
        //  消息历史（业务侧，供 ContextProvider 消费）
        // ══════════════════════════════════════════════════════════

        /** 消息历史 [{role, content, tool_call_id?}] */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  V3 回合执行收集物（一次用户消息 = 一次 ADK 托管循环 runAsync）
        // ══════════════════════════════════════════════════════════

        /** 回合累积可见文本（SSE 分片已即时发送；此处供同步 chat 与 done/result 使用） */
        @Builder.Default
        private StringBuilder visibleText = new StringBuilder();

        /** 当前一次 LLM 调用的可见文本缓冲（聚合事件到达 = 一次调用结束，转入 utterances） */
        @Builder.Default
        private StringBuilder currentUtterance = new StringBuilder();

        /** 当前缓冲中"尚未转入 utterances"的字符数（聚合事件边界判定用） */
        @Builder.Default
        private int utteranceCharsSinceAggregated = 0;

        /** 逐 LLM 调用的助手可见文本段（UserFeedbackNode 逐条落库 chat_message） */
        @Builder.Default
        private List<String> assistantUtterances = new ArrayList<>();

        /** 本轮 FunctionResponse 结果集（ToolCallNode 统一执行工具副作用） */
        @Builder.Default
        private List<EventTextUtil.ToolResultView> toolResultsCollected = new ArrayList<>();

        /** 工具调用总次数（回合统计） */
        @Builder.Default
        private int toolCallCount = 0;

        // ══════════════════════════════════════════════════════════
        //  终止状态与结果
        // ══════════════════════════════════════════════════════════

        /** 终止原因：completed / error（托管循环熔断等异常在 AiCallNode 置位） */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        /** 最终结果 DTO */
        private ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void appendUserMessage(String content) {
            messageHistory.add(Map.of("role", "user", "content", content));
        }

        public void incrementToolCallCount() {
            toolCallCount++;
        }

        /** 追加一条工具结果到收集集（AiCallNode 事件中继调用） */
        public void collectToolResult(EventTextUtil.ToolResultView view) {
            toolResultsCollected.add(view);
        }
    }

}

package jainingfei.site.ai.agent.cases.react.factory;

import jainingfei.site.ai.agent.api.dto.ReActResultDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 工厂 + 动态上下文。
 *
 * <p>DynamicContext 是贯穿整个 ReAct 节点链路的运行时状态容器，保存：
 * <ul>
 *   <li>会话信息（sessionId, userId, agentId, terminalSessionId）</li>
 *   <li>消息历史（messageHistory）和当前轮工具调用/结果</li>
 *   <li>ReAct 循环状态（currentStep, maxSteps, maxToolCalls）</li>
 *   <li>SSE 发射器和 AI 响应缓冲</li>
 *   <li>终止状态（stopReason, errorMessage）</li>
 * </ul>
 */
public class DefaultReActFactory {

    /**
     * ReAct 运行时动态上下文。
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

        /** SSE 事件发射器 */
        private SseEmitter emitter;

        // ══════════════════════════════════════════════════════════
        //  消息历史
        // ══════════════════════════════════════════════════════════

        /** 消息历史 [{role, content, tool_call_id?}] */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /** 当前轮次的工具调用列表 */
        @Builder.Default
        private List<Map<String, Object>> currentToolCalls = new ArrayList<>();

        /** 当前轮次的工具执行结果列表 */
        @Builder.Default
        private List<Map<String, Object>> currentToolResults = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  ReAct 循环状态
        // ══════════════════════════════════════════════════════════

        /** 当前步数 */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** 最大步数 */
        @Builder.Default
        private int maxSteps = 50;

        /** 最大工具调用次数（总计） */
        @Builder.Default
        private int maxToolCalls = 200;

        /** 每轮最大工具调用次数 */
        @Builder.Default
        private int maxToolCallsPerRound = 10;

        /** 总工具调用次数 */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  AI 响应缓冲
        // ══════════════════════════════════════════════════════════

        /** 累积的文本响应 */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        // ══════════════════════════════════════════════════════════
        //  终止状态
        // ══════════════════════════════════════════════════════════

        /** 终止原因：completed / finish / max_steps / max_tool_calls / user_stop / idle_timeout / error */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        /** 最终结果 DTO */
        private ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  动态 Prompt 上下文（2-4 节新增）
        // ══════════════════════════════════════════════════════════

        /** 最近执行的命令记录（FIFO，最多 20 条，用于注入到动态 Prompt 中） */
        @Builder.Default
        private List<String> recentCommands = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void resetRoundBuffers() {
            currentToolCalls.clear();
            currentToolResults.clear();
        }

        @SuppressWarnings("unchecked")
        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendToolMessage(String toolCallId, String content) {
            appendMessage(Map.of("role", "tool", "tool_call_id", toolCallId, "content", content));
        }

        /** 记录最近执行的命令（FIFO，最多 20 条，超出淘汰最老的） */
        public void addRecentCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            recentCommands.add(command.trim());
            while (recentCommands.size() > 20) {
                recentCommands.remove(0);
            }
        }

    }

}

package jainingfei.site.ai.agent.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话治理参数——把原先散落在 7 个类里的 {@code @Value("${ai.session-governance.*}")} 收成一份配置。
 *
 * <pre>
 *   max-events / max-turns / max-*-text      框架层会话治理（CustomAdkSessionService 一线）
 *   context-token-budget                     业务侧历史裁剪预算（HybridReducer）
 *   loop.max-llm-calls                       单次 runAsync 的 LLM 调用上限（托管循环熔断）
 *   compact.*                                ADK 事件压缩（滚动摘要 + 覆盖区间剪枝）
 *   cold-start.replay-budget-chars           重启后历史回顾注入预算
 * </pre>
 *
 * <p>集中之后的好处：默认值只有一处、单测可直接 new、改配置不用翻 7 个类；
 * 各消费者（会话服务、Runner 工厂、摘要器、历史回顾、上下文服务、案例层节点）统一注入本对象。
 */
@Data
@ConfigurationProperties(prefix = "ai.session-governance", ignoreInvalidFields = true)
public class SessionGovernanceProperties {

    /** Session 最多保留的 event 条数（防框架层历史无限膨胀） */
    private int maxEvents = 20;

    /** assistant/model 文本的最大保留长度 */
    private int maxAssistantText = 2000;

    /** tool 结果文本的最大保留长度（托管循环下 FunctionResponse 会进入下一轮 LLM 请求） */
    private int maxToolText = 4000;

    /** user 事件的最大保留长度（剥离动态段后；富化消息可能内嵌工具结果） */
    private int maxUserText = 4000;

    /** 最多保留的用户轮次数（轮次是比"消息条数"更自然的对话单位） */
    private int maxTurns = 4;

    /** 业务侧历史裁剪预算（tokens，HybridReducer；回合入口传 0 时回退到该默认值） */
    private int contextTokenBudget = 8000;

    private Loop loop = new Loop();

    private Compact compact = new Compact();

    private ColdStart coldStart = new ColdStart();

    /** 托管循环熔断 */
    @Data
    public static class Loop {
        /** 单次 runAsync 内 LLM 调用上限；超限以 LlmCallsLimitExceededException 结束回合 */
        private int maxLlmCalls = 30;
    }

    /** ADK 事件压缩（W4） */
    @Data
    public static class Compact {
        /** 总开关：开启后长度治理交给压缩引擎，丢头 trim 停用 */
        private boolean enabled = false;
        /** 触发阈值（tokens）：优先读聚合事件 usageMetadata 的真实 promptTokenCount */
        private int tokenThreshold = 48000;
        /** 近端保留的原始事件条数（其余更旧区间折叠为 [历史摘要]） */
        private int eventRetentionSize = 24;
        /** 摘要事件文本预算（确定性摘要器输出上限） */
        private int summaryMaxChars = 2500;
    }

    /** 冷启动一致性 */
    @Data
    public static class ColdStart {
        /** 重启后以旧 sessionId 重建空会话时，回放最近一段对话的字符预算 */
        private int replayBudgetChars = 12000;
    }
}

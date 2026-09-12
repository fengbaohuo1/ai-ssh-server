package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.CacheMetricsAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftAccess;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 渠道档案——"一条通道"的完整描述：模型实例 + 能力声明 + 两项私有能力的读法。
 *
 * <p>分层意图：
 * <pre>
 *   ChatModelFactory  按配置选档案（provider = 通道 id）
 *   ProviderProfile   回答"这条通道是什么、能做什么、私有能力怎么读"
 *   SpringAiLlmAdapter 只吃 ChatModel + 两个能力接口，不认识任何厂商类型
 * </pre>
 *
 * <p>加一条通道 = 新增一个实现类 + 在工厂的 switch 里注册一行；业务/装配/派发/编排代码不动。
 */
public interface ProviderProfile {

    /** 通道 id（与配置 chat-model.provider 对应） */
    String id();

    /** Spring AI 模型（通信层） */
    ChatModel chatModel();

    /** 能力声明 */
    ProviderCapabilities capabilities();

    /** 展示名（日志与前端）——取自能力声明的同一份通道元数据，避免两处维护 */
    default String displayName() {
        return capabilities().getDisplayName();
    }

    /**
     * 推理内容的通道专有读法；返回 {@code null} 表示走通用约定
     * （读 {@code AssistantMessage} 元数据键 {@code reasoningContent}）。
     */
    default ReasoningDraftAccess reasoningDraft() {
        return null;
    }

    /**
     * 缓存命中的通道专有读法；返回 {@code null} 表示通用字段
     * （{@code Usage.getCacheReadInputTokens()}）已够用。
     */
    default CacheMetricsAccess cacheMetrics() {
        return null;
    }
}

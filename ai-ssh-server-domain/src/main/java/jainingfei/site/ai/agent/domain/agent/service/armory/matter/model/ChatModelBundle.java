package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 模型包（provider bundle）——ADK 适配器与厂商实现之间的<b>唯一契约</b>。
 *
 * <p>为什么需要它：Spring AI 把"通信"统一到了 {@link ChatModel} 接口，但仍有四处
 * 厂商差异会漏到调用方，必须有人接住：
 * <pre>
 *   1. per-call options 多态
 *      provider 实现内部是硬强转：{@code (XxxChatOptions) prompt.getOptions()}
 *      （OpenAiChatModel / DeepSeekChatModel 一致），因此"塞进 Prompt 的 options
 *       必须是那家 provider 的子类"，且非空 options 时模型<b>不再与默认 options 合并</b>
 *        ——{@link #options(List)} 必须把默认模型/推理参数一并带上。
 *
 *   2. 思考草稿（reasoning_content）
 *      没有统一入口，provider 用自己的消息子类承载（DeepSeekAssistantMessage 等）
 *      ——{@link #reasoningContent(AssistantMessage)} 读取、{@link #assistantMessage} 构造。
 *
 *   3. 缓存命中 token
 *      通用 {@link Usage#getCacheReadInputTokens()} 未必填充，原生 usage 又是各家自己的类型
 *      ——{@link #cachedTokens(Usage)} 兜底。
 *
 *   4. 模型实例构建
 *      核心库没有"按配置运行期造模型"的工厂（自动配置是单例 bean + 静态 spring.ai.* 配置）
 *      ——由 {@link ChatModelFactory} 负责，实现类构造函数收取配置。
 * </pre>
 *
 * <p>于是分层变成：<b>适配器（FixedSpringAI）与装配/派发/编排层只依赖本接口</b>，
 * 厂商类型（DeepSeekApi / DeepSeekChatOptions / DeepSeekAssistantMessage / 原生 Usage）
 * 只允许出现在实现类里。接第二家模型 = 新写一个实现，其余代码一行不动。
 *
 * <p>案例（同一份 FixedSpringAI 适配器 + 两个 bundle）：
 * <pre>
 *   FixedSpringAI(DeepSeekChatModelBundle)  → DeepSeek 原生通道
 *   FixedSpringAI(OpenAiChatModelBundle)    → OpenAI 兼容通道（将来）
 * </pre>
 */
public interface ChatModelBundle {

    /**
     * 底层 Spring AI 模型（厂商无关接口）。
     * <p>适配器据此判断是否支持流式（{@code instanceof StreamingChatModel}）并取模型名做日志。
     */
    ChatModel chatModel();

    /**
     * 厂商标识，仅用于日志与排查（如 deepseek）。
     */
    String providerName();

    /**
     * 构造工具轮次的 per-call options。
     * <p>实现必须返回<b>本 provider 的 options 子类</b>，且携带默认模型/推理参数
     * （provider 在 options 非空时不会合并默认值）。
     *
     * @param toolCallbacks 本轮工具回调（ADK 工具经 ToolConverter 转换而来）
     */
    ChatOptions options(List<ToolCallback> toolCallbacks);

    /**
     * 读取响应消息里的思考草稿；该 provider 无此能力时返回 {@code null}。
     */
    String reasoningContent(AssistantMessage message);

    /**
     * 构造历史 assistant 消息（把草稿与工具调用放回 provider 认得的消息类型）。
     *
     * @param visibleText 可见正文（允许为空串，实现需按 provider 要求兜底非 null）
     * @param reasoning   思考草稿，可为 null
     * @param toolCalls   工具调用，可为空
     */
    AssistantMessage assistantMessage(String visibleText, String reasoning,
                                      List<AssistantMessage.ToolCall> toolCalls);

    /**
     * 从原生 usage 提取缓存命中 token；取不到返回 {@code null}。
     * <p>调用方（适配器）会先读通用的 {@link Usage#getCacheReadInputTokens()}，为空才兜底到这里。
     */
    Long cachedTokens(Usage usage);
}

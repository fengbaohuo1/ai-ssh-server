package jainingfei.site.ai.agent.domain.agent.service.armory.matter.model;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * 模型工厂——按配置造 {@link ChatModel}，<b>厂商中立</b>（代码里不出现任何厂商 SDK 类型）。
 *
 * <p>走的通道是 <b>OpenAI 协议</b>：厂商身份只体现在配置里的 {@code base-url}（+ model 名），
 * 因此新增/更换模型 = 改配置，不改代码：
 * <pre>
 *   base-url: https://api.deepseek.com       → DeepSeek
 *   base-url: https://api.moonshot.cn/v1     → Kimi
 *   base-url: http://127.0.0.1:8000/v1       → 本地 vLLM / Ollama(openai 兼容)
 * </pre>
 *
 * <p>为什么不需要"厂商包"：provider 之间的差异（推理内容回传、缓存命中统计、工具调用参数）
 * 由 {@code spring-ai-openai} 在协议层统一处理——它读 {@code reasoning_content} 放进
 * {@code AssistantMessage} 元数据、按元数据回写该字段、并把
 * {@code prompt_tokens_details.cached_tokens} 填进通用 {@code Usage.getCacheReadInputTokens()}。
 * 我们只在适配器里用这些通用出口即可（见 FixedSpringAI）。
 *
 * <p>一个必须显式打开的开关：{@code streamUsage(true)} —— OpenAI 协议下 usage（含缓存命中）
 * 只在流式响应的末块返回，且要求请求带 {@code stream_options.include_usage=true}；
 * 不开就看不到 token 与缓存命中统计（长会话压缩也会失去真实 promptTokenCount）。
 *
 * <p>三条链路共用本入口，只差调参：
 * <pre>
 *   createMain(cfg)                            主链路   reasoningEffort 取配置（如 High）
 *   create(cfg, deterministic(null))           规划器   不设推理强度（要快、要稳的 JSON 输出）
 *   create(cfg, tuning(temperature = 0.1))     意图识别 只要确定性
 * </pre>
 */
@Slf4j
@Component
public class ChatModelFactory {

    /**
     * 主链路模型：推理强度取配置文件，并开启流式 usage。
     *
     * @param chatModelConfig 配置文件 chat-model（base-url / api-key / model / reasoning-effort）
     */
    public ChatModel createMain(AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
        return create(chatModelConfig, ModelTuning.builder()
                .reasoningEffort(chatModelConfig.getReasoningEffort())
                .build());
    }

    /**
     * 通用入口：按配置 + 调参造模型。
     *
     * @param chatModelConfig 配置文件 chat-model
     * @param tuning          调参（推理强度 / 温度），null = 全部跟随服务端默认
     */
    public ChatModel create(AiAgentConfigTableVO.Module.ChatModel chatModelConfig, ModelTuning tuning) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .model(chatModelConfig.getModel())
                // 流式 usage：缓存命中与 token 统计的观测开关（协议要求显式打开）
                .streamUsage(true);

        String reasoningEffort = tuning == null ? null : tuning.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            // 仅推理模型生效，非推理模型由服务端忽略（未知取值也不会本地抛错，交给服务端裁决）
            options.reasoningEffort(reasoningEffort);
        }
        if (tuning != null && tuning.getTemperature() != null) {
            options.temperature(tuning.getTemperature());
        }

        ChatModel chatModel = OpenAiChatModel.builder().options(options.build()).build();
        log.info("模型装配完成 | 通道:openai-compatible | model:{} | baseUrl:{} | reasoningEffort:{} | temperature:{}",
                chatModelConfig.getModel(), chatModelConfig.getBaseUrl(),
                StringUtils.defaultIfBlank(reasoningEffort, "默认"),
                tuning == null || tuning.getTemperature() == null ? "默认" : tuning.getTemperature());
        return chatModel;
    }
}

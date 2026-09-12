package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 读取缓存命中 token 的能力接口——由通道档案实现。
 *
 * <p>为什么需要它：Spring AI 把 {@code prompt_tokens_details.cached_tokens} 暴露成通用字段
 * {@code Usage.getCacheReadInputTokens()}，但**是否填充由各 provider 模块自己决定**：
 * OpenAI 协议通道会填，原生通道可能不填。不填时只能退回读该通道的原生 usage 对象。
 *
 * <p>返回 {@code null} 表示本通道拿不到该指标（不影响对话，只影响观测）。
 */
public interface CacheMetricsAccess {

    /**
     * 从原生 usage 中读取缓存命中 token 数。
     *
     * @param usage spring-ai 的 usage（其 {@code getNativeUsage()} 是通道原生对象）
     * @return 缓存命中 token 数；拿不到返回 null
     */
    Long cachedTokens(Usage usage);
}

package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Usage 观测——token 消耗、缓存命中与 usageMetadata 映射（教程 2-9 的可验证性来源）。
 *
 * <p>三条出口：
 * <pre>
 *   ① 流式抓取：流式 usage 只在末块返回，而末块通常无文本（会被空白过滤丢掉），
 *      所以必须在过滤<b>之前</b>观察每个 chunk，保留最后一次出现的 usage
 *   ② 日志：LLM usage | promptTokens | completionTokens | totalTokens | cachedTokens
 *   ③ usageMetadata：随聚合事件落库，ADK 事件压缩（tail-retention）据此读真实 promptTokenCount
 * </pre>
 *
 * <p>缓存命中优先读 Spring AI 的通用字段 {@code Usage.getCacheReadInputTokens()}；
 * 通道是否填充由 {@link CacheMetricsAccess} 兜底（原生通道的通用字段未填，需要读原生 usage）。
 */
@Slf4j
public class UsageReporter {

    /** 流式路径：在空白过滤前观察 chunk，保留最后一次出现的 usage（末块含完整统计） */
    public void observe(ChatResponse response, AtomicReference<Usage> usageRef) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            usageRef.set(usage);
        }
    }

    /** 输出一次模型调用的 token 消耗（含缓存命中数），用于验证 Prompt Cache 收益 */
    public void log(Usage usage, CacheMetricsAccess access) {
        if (usage == null) {
            return;
        }
        log.info("LLM usage | promptTokens: {} | completionTokens: {} | totalTokens: {} | cachedTokens: {}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                cachedTokens(usage, access));
    }

    /** spring-ai Usage → genai usageMetadata（压缩引擎读取真实 promptTokenCount） */
    public GenerateContentResponseUsageMetadata toMetadata(Usage usage, CacheMetricsAccess access) {
        if (usage == null) {
            return null;
        }
        try {
            GenerateContentResponseUsageMetadata.Builder builder = GenerateContentResponseUsageMetadata.builder();
            if (usage.getPromptTokens() != null) builder.promptTokenCount(usage.getPromptTokens());
            if (usage.getCompletionTokens() != null) builder.candidatesTokenCount(usage.getCompletionTokens());
            if (usage.getTotalTokens() != null) builder.totalTokenCount(usage.getTotalTokens());
            Long cached = cachedTokens(usage, access);
            if (cached != null) builder.cachedContentTokenCount(cached.intValue());
            return builder.build();
        } catch (Exception e) {
            log.debug("usage → usageMetadata 映射失败: {}", e.getMessage());
            return null;
        }
    }

    /** 缓存命中数：通用字段优先，未填充时交通道能力兜底 */
    public Long cachedTokens(Usage usage, CacheMetricsAccess access) {
        if (usage == null) {
            return null;
        }
        try {
            Long cached = usage.getCacheReadInputTokens();
            if (cached != null) {
                return cached;
            }
            return access == null ? null : access.cachedTokens(usage);
        } catch (Exception e) {
            log.debug("提取缓存命中 token 失败: {}", e.getMessage());
            return null;
        }
    }
}

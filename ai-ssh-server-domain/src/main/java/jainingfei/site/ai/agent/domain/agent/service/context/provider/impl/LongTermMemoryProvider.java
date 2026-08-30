package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆上下文提供者（order=25）。
 *
 * <p>在 ReAct 循环构建 Prompt 时，自动召回与当前对话相关的长期记忆，
 * 输出 {@code longTermMemorySummary} 字段，经 DynamicPromptBuilder 渲染为 [长期记忆] 段落。
 *
 * <p>排序 order=25，位于 TaskProvider(20) 之后、MilestoneProvider(30) 之前，
 * 表达"先确定任务、再召回记忆、最后收集里程碑"的语义优先级。
 *
 * <p>召回查询构造策略：取消息历史中首条用户消息（初始目标）+ 最近用户消息（当前需求）拼接，
 * 保证召回的记忆既与全局任务相关，又与当前轮次相关。
 *
 * @see ContextProvider
 * @see ILongTermMemoryService#buildMemorySummary
 */
@Component
public class LongTermMemoryProvider implements ContextProvider {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-memory";
    }

    /** 排序权重 25，位于 TaskProvider(20) 与 MilestoneProvider(30) 之间 */
    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = buildQuery(messageHistory);
        String summary = longTermMemoryService.buildMemorySummary(userId, query, 5);
        if (!summary.isBlank()) {
            result.put("longTermMemorySummary", summary);
        }
        return result;
    }

    /**
     * 构造召回查询：首条用户消息（初始目标）+ 最近用户消息（当前需求）拼接。
     *
     * <p>首条代表会话的初始目标（如"排查 nginx 502"），最近一条代表当前轮次的具体需求
     * （如"再看看 redis 连接池"），两者拼接覆盖会话内的主题漂移。
     */
    private String buildQuery(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }

        String latestUser = "";
        String firstUser = "";
        for (Map<String, Object> message : messageHistory) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (firstUser.isBlank()) {
                firstUser = content;
            }
            latestUser = content;
        }
        return (firstUser + " " + latestUser).trim();
    }
}

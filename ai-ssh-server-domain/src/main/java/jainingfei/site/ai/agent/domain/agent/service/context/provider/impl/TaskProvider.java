package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文提供者（order=20）。
 *
 * <p>从消息历史中提取首条 user 消息作为"当前任务描述"，让模型在长对话、
 * 多轮工具调用后仍能记住最初的目标（防"任务漂移"）。
 *
 * <p>设计说明：取"首条"而非"最近"——首条用户消息代表会话的初始目标；
 * 后续 user 消息多为补充/纠偏，已由 MilestoneProvider 覆盖。
 * 为防止多轮对话中历史被裁剪或前缀污染，RootNode 初始化时已把原始任务
 * 存入 DynamicContext.originalUserTask（预留；此处仍从历史扫描并做前缀清洗）。
 */
@Component
public class TaskProvider implements ContextProvider {

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        if (messageHistory == null) {
            return result;
        }

        messageHistory.stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst()
                .ifPresent(m -> {
                    Object content = m.get("content");
                    if (content != null) {
                        result.put("taskDescription", stripDynamicPrefix(String.valueOf(content)));
                    }
                });
        return result;
    }

    /** 清洗动态前缀：富化消息形如"前缀\n---\n原始消息"，只保留分隔线之后的部分 */
    private String stripDynamicPrefix(String text) {
        if (text == null) return null;
        if (text.contains("\n---\n")) {
            String[] parts = text.split("\\n---\\n", 2);
            return parts.length == 2 ? parts[1].trim() : text;
        }
        return text;
    }
}

package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import jainingfei.site.ai.agent.domain.agent.service.intent.IntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文提供者（order=20）。
 *
 * <p>提供"当前任务描述"，让模型在长对话、多轮工具调用后仍能记住目标（防"任务漂移"）。
 * 两级来源：
 * <ol>
 *   <li>意图识别的任务锚点（2-8 对齐教程）——IntentService 维护的 TaskStateVO 随
 *       反馈回路/纠偏实时更新，是最新鲜、最准确的任务描述</li>
 *   <li>兜底：消息历史中首条 user 消息（前缀清洗后）——任务锚点尚未建立时使用</li>
 * </ol>
 */
@Slf4j
@Component
public class TaskProvider implements ContextProvider {

    @Resource
    private IntentService intentService;

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

        // 1. 意图任务锚点优先——反馈回路会随用户纠偏更新，比首条消息更"新鲜"
        if (sessionId != null) {
            try {
                TaskStateVO taskState = intentService.getTaskState(sessionId);
                if (taskState != null && taskState.getTaskDescription() != null
                        && !taskState.getTaskDescription().isBlank()) {
                    result.put("taskDescription", taskState.getTaskDescription());
                    return result;
                }
            } catch (Exception e) {
                log.debug("读取意图任务锚点失败，回退首条消息 sessionId={}", sessionId, e);
            }
        }

        // 2. 兜底：首条 user 消息（初始目标）
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

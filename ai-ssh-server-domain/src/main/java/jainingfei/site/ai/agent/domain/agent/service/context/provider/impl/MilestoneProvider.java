package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic.MilestoneTracker;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 里程碑上下文提供者（order=30）。
 *
 * <p>把会话中的"关键事件"（用户纠偏、任务切换、工具报错等）注入上下文，
 * 让模型感知对话过程中的重要转折点，避免重复犯错。
 * 数据源是 {@link MilestoneTracker}（正则识别 + 会话级缓存）。
 */
@Component
public class MilestoneProvider implements ContextProvider {

    @Resource
    private MilestoneTracker milestoneTracker;

    @Override
    public String getName() {
        return "milestone";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);
        result.put("milestoneVOS", milestoneVOS);
        return result;
    }
}

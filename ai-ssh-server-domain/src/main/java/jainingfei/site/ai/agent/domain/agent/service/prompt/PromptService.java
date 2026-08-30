package jainingfei.site.ai.agent.domain.agent.service.prompt;

import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.PromptContextVO;
import jainingfei.site.ai.agent.domain.agent.service.IChatContextService;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic.MilestoneTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提示词领域服务实现——收口动态 Prompt 构建的全部能力。
 *
 * <p>组合三个内部组件：
 * <ul>
 *   <li>{@link DynamicPromptBuilder} —— 组装结构化消息前缀</li>
 *   <li>{@link MilestoneTracker} —— 检测并缓存关键事件</li>
 *   <li>{@link IChatContextService} —— 聚合各 ContextProvider（环境 / 任务 / 里程碑 / 工具摘要）</li>
 * </ul>
 *
 * <p>Case 层只依赖 {@link IPromptService} 接口，不接触内部组件。
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    @Resource
    private MilestoneTracker milestoneTracker;

    @Resource
    private IChatContextService chatContextService;

    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String userId,
                                       String terminalSessionId, List<String> recentCommands,
                                       List<Map<String, Object>> messageHistory, String intentLabel) {
        // 1. 聚合各 ContextProvider（环境 / 任务 / 里程碑 / 工具摘要）→ PromptContextVO
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(
                sessionId, userId, terminalSessionId, messageHistory);
        promptContextVO.setRecentCommands(recentCommands);
        // 2. 意图标签流转：intentLabel → PromptContextVO.intentLabel → DynamicPromptBuilder 前缀
        promptContextVO.setIntentLabel(intentLabel);

        // 3. 生成消息前缀
        String prefix = dynamicPromptBuilder.buildMessagePrefix(promptContextVO);

        if (prefix.isEmpty()) {
            return userMessage;
        }

        return prefix + "\n---\n" + userMessage;
    }

    @Override
    public String buildIncrementalPrefix(String sessionId, long sinceMilestoneTs,
                                         List<String> recentCommands, int sinceCommandCount,
                                         int sinceToolResultCount) {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        // 1. 新增命令（第 1 轮已全量注入过前 sinceCommandCount 条，只取之后的增量）
        if (recentCommands != null && sinceCommandCount < recentCommands.size()) {
            sb.append("[本轮新增命令]\n");
            for (int i = sinceCommandCount; i < recentCommands.size(); i++) {
                sb.append("- ").append(recentCommands.get(i)).append("\n");
            }
            hasContent = true;
        }

        // 2. 新增里程碑（按时间戳水位过滤；取全量 50 条上限，避免 getRecent(10) 截断漏掉增量）
        List<MilestoneVO> freshMilestones = new ArrayList<>();
        for (MilestoneVO m : milestoneTracker.getRecent(sessionId, 50)) {
            if (m.getTimestamp() > sinceMilestoneTs) {
                freshMilestones.add(m);
            }
        }
        if (!freshMilestones.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[新增关键事件]\n");
            for (MilestoneVO m : freshMilestones) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        // 3. 工具执行时间线（2-8/修复①）：自水位以来的执行流水一行式，
        //    显式循环下 [工具执行摘要] 全量段不会进入后续轮次，靠此增量补上长程轨迹
        List<String> toolTimeline = chatContextService.getToolResultTimeline(sessionId, sinceToolResultCount);
        if (!toolTimeline.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[工具执行记录]\n");
            for (String line : toolTimeline) {
                sb.append(line).append("\n");
            }
            hasContent = true;
        }

        return hasContent ? sb.toString() : "";
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }
}

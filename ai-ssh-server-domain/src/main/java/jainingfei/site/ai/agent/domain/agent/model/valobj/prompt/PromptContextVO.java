package jainingfei.site.ai.agent.domain.agent.model.valobj.prompt;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 动态 Prompt 上下文值对象——承载环境信息、最近命令、里程碑事件、
 * 当前任务、工具执行摘要五类动态上下文。
 *
 * <p>由 {@code ChatContextService.buildPromptContext()} 聚合各 ContextProvider
 * 的输出组装，供 {@code DynamicPromptBuilder} 翻译为结构化消息前缀。
 */
@Data
@Builder
public class PromptContextVO {

    /** 服务器标识（预留，当前项目未使用） */
    private String serverInfo;

    /** 操作系统信息（uname -srm 输出） */
    private String osInfo;

    /** 当前登录用户（whoami 输出） */
    private String currentUser;

    /** 当前工作目录（pwd 输出） */
    private String currentDirectory;

    /** 最近执行的命令列表（FIFO，最多 20 条） */
    private List<String> recentCommands;

    /** 最近里程碑事件（最多 10 条） */
    private List<MilestoneVO> milestoneVOS;

    /** 工具执行摘要——会话级缓存，跨轮次/跨对话回顾"之前执行过什么" */
    private String toolResultSummary;

    /** 当前任务描述（首条用户消息）——防长对话任务漂移 */
    private String taskDescription;

    /**
     * 当前意图标签（由意图识别系统经 PromptService.buildEnrichedMessage(intentLabel) 注入）。
     * <p>
     * 由 {@code DynamicPromptBuilder} 渲染为消息前缀 "[用户意图] xxx"，让主模型感知意图但不强制路由。
     */
    private String intentLabel;
}

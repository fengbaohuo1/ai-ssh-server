package jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic;

import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.PromptContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态 Prompt 构建器——将 {@link PromptContextVO} 中的环境信息、最近命令、里程碑事件、
 * 当前任务、工具执行摘要翻译为模型可读的结构化消息前缀。
 *
 * <p>两类构建方式：
 * <ul>
 *   <li>{@link #buildMessagePrefix} —— 拼在用户消息前面（当前使用，因为 ADK Runner 的
 *       system instruction 在 Agent 装配阶段已固定，运行期改不了）</li>
 *   <li>{@link #build} —— 追加到 system instruction 末尾（预留）</li>
 * </ul>
 *
 * <p>核心原则：<b>有才拼、没有不拼</b>——第一轮对话无历史时返回空串，不塞空标题浪费 token。
 */
@Slf4j
@Component
public class DynamicPromptBuilder {

    /**
     * 将动态上下文构建为用户消息前缀。
     *
     * <p>输出格式（有内容才输出对应段落）：
     * <pre>
     * [系统环境]
     * 系统: Linux 5.15.0-91-generic x86_64
     * 用户: root
     * 目录: /opt/app
     *
     * [最近执行的命令]
     * - ls -la
     *
     * [关键事件]
     * - [USER_CORRECTION] 不对，换一种方式
     * </pre>
     *
     * @param ctx 动态上下文，为 null 时返回空串
     * @return 富化消息前缀，无内容时返回空串
     */
    public String buildMessagePrefix(PromptContextVO ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        // [系统环境] 段落
        if (!isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory())) {
            sb.append("[系统环境]\n");
            if (!isEmpty(ctx.getServerInfo()))       sb.append("服务器: ").append(ctx.getServerInfo()).append("\n");
            if (!isEmpty(ctx.getOsInfo()))           sb.append("系统: ").append(ctx.getOsInfo()).append("\n");
            if (!isEmpty(ctx.getCurrentUser()))      sb.append("用户: ").append(ctx.getCurrentUser()).append("\n");
            if (!isEmpty(ctx.getCurrentDirectory())) sb.append("目录: ").append(ctx.getCurrentDirectory()).append("\n");
            hasContent = true;
        }

        // [最近执行的命令] 段落
        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[最近执行的命令]\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        // [当前任务] 段落——首条用户消息，防长对话任务漂移
        if (!isEmpty(ctx.getTaskDescription())) {
            if (hasContent) sb.append("\n");
            sb.append("[当前任务]\n").append(ctx.getTaskDescription()).append("\n");
            hasContent = true;
        }

        // [关键事件] 段落
        if (ctx.getMilestoneVOS() != null && !ctx.getMilestoneVOS().isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[关键事件]\n");
            for (MilestoneVO m : ctx.getMilestoneVOS()) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        // [工具执行摘要] 段落——会话级工具结果回顾（跨轮次/跨对话）
        if (!isEmpty(ctx.getToolResultSummary())) {
            if (hasContent) sb.append("\n");
            sb.append("[工具执行摘要]\n").append(ctx.getToolResultSummary()).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";

        String prefix = sb.toString();
        log.debug("构建消息前缀，长度: {}", prefix.length());
        return prefix;
    }

    /**
     * 将动态上下文追加到基础 system instruction 末尾（预留，当前未使用）。
     *
     * @param baseInstruction 基础系统指令文本
     * @param ctx             动态上下文，为 null 时直接返回基础指令
     * @return 拼接后的完整指令
     */
    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) return baseInstruction;

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getServerInfo()) && isEmpty(ctx.getOsInfo())
                && isEmpty(ctx.getCurrentUser()) && isEmpty(ctx.getCurrentDirectory())) return;
        sb.append("\n\n## 当前环境信息\n");
        if (!isEmpty(ctx.getServerInfo()))       sb.append("- 服务器: ").append(ctx.getServerInfo()).append("\n");
        if (!isEmpty(ctx.getOsInfo()))           sb.append("- 操作系统: ").append(ctx.getOsInfo()).append("\n");
        if (!isEmpty(ctx.getCurrentUser()))      sb.append("- 当前用户: ").append(ctx.getCurrentUser()).append("\n");
        if (!isEmpty(ctx.getCurrentDirectory())) sb.append("- 工作目录: ").append(ctx.getCurrentDirectory()).append("\n");
    }

    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}

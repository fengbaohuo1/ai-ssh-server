package jainingfei.site.ai.agent.domain.agent.service.prompt;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.PromptContextVO;
import jainingfei.site.ai.agent.domain.agent.service.IPromptService;
import jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic.MilestoneTracker;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词领域服务实现——收口动态 Prompt 构建的全部能力。
 *
 * <p>组合三个内部组件：
 * <ul>
 *   <li>{@link DynamicPromptBuilder} —— 组装结构化消息前缀</li>
 *   <li>{@link MilestoneTracker} —— 检测并缓存关键事件</li>
 *   <li>{@link ISshTerminalService} —— SSH 终端通道，采集远程环境信息</li>
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
    private ISshTerminalService sshTerminalService;

    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId,
                                        String terminalSessionId, List<String> recentCommands) {
        // 1. 从 SSH 终端采集环境信息 + 取里程碑 → 组装 PromptContextVO
        PromptContextVO promptContextVO = buildPromptContext(sessionId, terminalSessionId, recentCommands);

        // 2. 生成消息前缀
        String prefix = dynamicPromptBuilder.buildMessagePrefix(promptContextVO);

        if (prefix.isEmpty()) {
            return userMessage;
        }

        return prefix + "\n---\n" + userMessage;
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  环境采集
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建动态 Prompt 上下文值对象。
     *
     * <p>通过 SSH 终端实时采集三类环境信息：
     * <ul>
     *   <li>{@code uname -srm} → osInfo（操作系统+架构+内核版本）</li>
     *   <li>{@code whoami} → currentUser（当前登录用户）</li>
     *   <li>{@code pwd} → currentDirectory（当前工作目录）</li>
     * </ul>
     *
     * <p>工程细节：
     * <ul>
     *   <li>使用 {@code execCommand}（ChannelExec）而非 {@code executeCommand}（ChannelShell），
     *       环境采集是一次性命令，ChannelExec 无回显/ANSI/prompt 污染</li>
     *   <li>每条采集命令独立 try-catch——环境采集是"锦上添花"非"必需成功"，失败留空不阻塞主流程</li>
     * </ul>
     *
     * @param sessionId          对话会话 ID（传给 MilestoneTracker）
     * @param terminalSessionId  SSH 终端会话 ID（即 connectionId），为 null 或空时跳过环境采集
     * @param recentCommands     最近执行的命令列表
     * @return 组装完成的动态上下文值对象
     */
    private PromptContextVO buildPromptContext(String sessionId, String terminalSessionId,
                                               List<String> recentCommands) {
        String osInfo = "";
        String currentUser = "";
        String currentDirectory = "";

        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            osInfo = safeExec(terminalSessionId, "uname -srm");
            currentUser = safeExec(terminalSessionId, "whoami");
            currentDirectory = safeExec(terminalSessionId, "pwd");
        }

        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);

        return PromptContextVO.builder()
                .osInfo(osInfo)
                .currentUser(currentUser)
                .currentDirectory(currentDirectory)
                .recentCommands(recentCommands)
                .milestoneVOS(milestoneVOS)
                .build();
    }

    /** 安全执行采集命令——失败返回空串，不抛异常 */
    private String safeExec(String terminalSessionId, String command) {
        try {
            String raw = sshTerminalService.execCommand(terminalSessionId, command);
            return raw != null ? raw.trim() : "";
        } catch (Exception e) {
            log.debug("环境采集失败: command={}, error={}", command, e.getMessage());
            return "";
        }
    }
}

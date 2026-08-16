package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端状态上下文提供者（order=10，最先执行）。
 *
 * <p>通过 SSH 终端实时采集远程服务器的环境信息（OS / 用户 / 工作目录），
 * 让模型"知道自己在哪台机器上操作"。该逻辑从 PromptService 下沉至此，
 * PromptService 不再直接依赖 ISshTerminalService。
 *
 * <p>容错设计：每条命令独立 try-catch（safeExec），单条失败仅该字段留空——
 * 环境采集是"锦上添花"，绝不阻断主流程。
 */
@Slf4j
@Component
public class TerminalStateProvider implements ContextProvider {

    @Resource
    private ISshTerminalService sshTerminalService;

    @Override
    public String getName() {
        return "terminal-state";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return result;
        }

        result.put("osInfo", safeExec(terminalSessionId, "uname -srm"));
        result.put("currentUser", safeExec(terminalSessionId, "whoami"));
        result.put("currentDirectory", safeExec(terminalSessionId, "pwd"));
        return result;
    }

    /** 安全执行采集命令——失败返回空串，不抛异常 */
    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String raw = sshTerminalService.execCommand(terminalSessionId, cmd);
            return raw != null ? raw.trim() : "";
        } catch (Exception e) {
            log.debug("环境采集失败: command={}, error={}", cmd, e.getMessage());
            return "";
        }
    }
}

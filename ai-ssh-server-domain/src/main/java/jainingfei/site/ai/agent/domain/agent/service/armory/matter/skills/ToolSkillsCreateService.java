package jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Skills 工具集装配接口 — 将 YAML 配置的 skills 目录/资源转为 Spring AI {@link ToolCallback}。
 */
public interface ToolSkillsCreateService {

    /**
     * 读取所有 skills 的 SKILL.md 指令内容，拼接为一段 system prompt 文本，
     * 用于注入 Agent 的 instruction，确保 LLM 理解每个 skill 的用途和调用方式。
     */
    String getSkillsInstruction(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception;

    /**
     * 返回 skills 的原始 {@link ToolCallback} 列表，
     * 用于配置到 Spring AI 的 {@code ToolCallingManager}（模型层面的 function calling）。
     */
    List<ToolCallback> getToolCallbacks(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception;
}

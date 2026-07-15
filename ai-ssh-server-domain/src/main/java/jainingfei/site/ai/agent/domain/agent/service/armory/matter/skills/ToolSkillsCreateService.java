package jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills;

import com.google.adk.tools.BaseToolset;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * Skills 工具集装配接口 — 将 YAML 配置的 skills 转为 ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}。
 */
public interface ToolSkillsCreateService {

    /** 读取所有 skills 的 SKILL.md，拼接为一段 system prompt，注入 Agent instruction */
    String getSkillsInstruction(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception;

    /** 返回 skills 的 ADK BaseToolset，交给 ADK 管理工具调用 */
    BaseToolset createToolset(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception;
}

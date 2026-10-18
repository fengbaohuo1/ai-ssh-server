package jainingfei.site.ai.agent.api;

import jainingfei.site.ai.agent.api.dto.AgentConfigFileDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigFileUpdateDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigModelDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigModelUpdateDTO;
import jainingfei.site.ai.agent.api.dto.AgentConfigWriteResultDTO;
import jainingfei.site.ai.agent.api.response.Response;

/**
 * Agent 配置读写服务（trigger 实现，api 层只放契约与 DTO）。
 *
 * <p>两个页面接口：
 * <pre>
 *   GET/PUT /api/v1/agent/config/file   Agent YAML 配置（agent.yml 原文）
 *   GET/PUT /api/v1/agent/config/model  模型通道配置（llm.yml 覆盖层）
 * </pre>
 * 写接口统一返回 {@code restartRequired=true}：配置文件在启动期被读取，保存后需重启生效。
 */
public interface IAgentConfigService {

    /** 读取 agent.yml 原文（不存在时先由模板初始化） */
    Response<AgentConfigFileDTO> queryAgentConfigFile();

    /** 保存 agent.yml 原文（SnakeYAML 校验 + 备份 + 原子替换） */
    Response<AgentConfigWriteResultDTO> updateAgentConfigFile(AgentConfigFileUpdateDTO request);

    /** 读取有效模型配置（agent.yml 与 llm.yml 合并，api-key 只回传是否已配置） */
    Response<AgentConfigModelDTO> queryModelConfig();

    /** 保存模型配置（只写 llm.yml 的 chat-model 覆盖层） */
    Response<AgentConfigWriteResultDTO> updateModelConfig(AgentConfigModelUpdateDTO request);
}

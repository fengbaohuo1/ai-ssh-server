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
 * <p>接口：
 * <pre>
 *   GET/PUT /api/v1/agent/config/file    Agent YAML 配置（agent.yml 原文）
 *   GET/PUT /api/v1/agent/config/model   模型通道配置（llm.yml 覆盖层）
 *   POST    /api/v1/agent/config/reload  手动热重装（改过磁盘上的配置文件后使用）
 * </pre>
 * 写接口保存成功后立即热重装智能体（无需重启进程）：返回 {@code applied=true}；
 * 若热重装失败则文件仍已保存、旧装配继续服务，返回 {@code applied=false} 与失败原因。
 */
public interface IAgentConfigService {

    /** 读取 agent.yml 原文（不存在时先由模板初始化） */
    Response<AgentConfigFileDTO> queryAgentConfigFile();

    /** 保存 agent.yml 原文（SnakeYAML 校验 + 备份 + 原子替换），成功后热重装 */
    Response<AgentConfigWriteResultDTO> updateAgentConfigFile(AgentConfigFileUpdateDTO request);

    /** 读取有效模型配置（agent.yml 与 llm.yml 合并，api-key 只回传是否已配置） */
    Response<AgentConfigModelDTO> queryModelConfig();

    /** 保存模型配置（只写 llm.yml 的 chat-model 覆盖层），成功后热重装 */
    Response<AgentConfigWriteResultDTO> updateModelConfig(AgentConfigModelUpdateDTO request);

    /** 手动热重装：重新读取磁盘配置并重装智能体（配置被外部修改、或上次热重装失败后重试用） */
    Response<AgentConfigWriteResultDTO> reloadAgentConfig();
}

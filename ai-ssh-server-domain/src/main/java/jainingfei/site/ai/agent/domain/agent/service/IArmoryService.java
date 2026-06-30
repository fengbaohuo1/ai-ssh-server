package jainingfei.site.ai.agent.domain.agent.service;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

public interface IArmoryService {
    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;
}

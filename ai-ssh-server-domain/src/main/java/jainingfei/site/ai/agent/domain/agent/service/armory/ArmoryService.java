package jainingfei.site.ai.agent.domain.agent.service.armory;

import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.IArmoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.node.AgentNode;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ArmoryService implements IArmoryService {
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /**
     * AgentNode 的工具集生命周期：装配成功后回收上一版 MCP/技能工具集。
     * <p>放在装配链路之外而不是节点内部，保证「先成功、再回收」——
     * 装配抛异常时不会走到回收，旧 Runner/旧工具集继续服务。
     */
    @Resource
    private AgentNode agentNode;

    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        for (AiAgentConfigTableVO aiAgentConfigTableVO : tables) {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO>
                    handler = defaultArmoryFactory.armoryStrategyHandler();
            DefaultArmoryFactory.DynamicContext dynamicContext = new DefaultArmoryFactory.DynamicContext();
            handler.apply(ArmoryCommandEntity.builder()
                    .aiAgentConfigTableVO(aiAgentConfigTableVO)
                    .build(), dynamicContext);

            // 装配成功后才登记本版工具集并关闭上一版；失败路径不回收（保留旧装配可用）
            agentNode.recyclePreviousMcpToolsets(
                    aiAgentConfigTableVO.getAppName(), dynamicContext.getMcpToolsets());
        }
    }
}

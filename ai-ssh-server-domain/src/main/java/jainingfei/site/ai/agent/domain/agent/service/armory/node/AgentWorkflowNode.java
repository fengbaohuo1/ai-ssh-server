package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.enums.AgentTypeEnum;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.node.workflow.LoopAgentNode;
import jainingfei.site.ai.agent.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import jainingfei.site.ai.agent.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {
    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private RunnerNode runnerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();
        if (ObjectUtils.isEmpty(agentWorkflows) || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        if (currentAgentWorkflow == null) {
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.fromType(type);
        if (agentTypeEnum == null) {
            throw new RuntimeException("agentWorkflow type is error!");
        }

        return switch (agentTypeEnum) {
            case Loop -> loopAgentNode;
            case Parallel -> parallelAgentNode;
            case Sequential -> sequentialAgentNode;
        };
    }
}

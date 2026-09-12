package jainingfei.site.ai.agent.domain.agent.service.armory.factory;

import com.google.adk.agents.BaseAgent;

import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import jainingfei.site.ai.agent.domain.agent.service.armory.node.RootNode;
import lombok.*;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DefaultArmoryFactory {
    @Resource
    private RootNode rootNode;

    @Resource
    private ApplicationContext applicationContext;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @EqualsAndHashCode(callSuper = true)
    public static class DynamicContext extends jianingfei.site.wrench.design.framework.tree.DynamicContext {
        /**
         * 模型通道档案（由 ChatModelNode 经 ChatModelFactory 装配：provider 选通道，档案带模型与能力）
         */
        private ProviderProfile providerProfile;
        /**
         * 智能体配置组
         */
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        /**
         * MCP 工具集（ChatModelNode 装配）
         */
        private List<BaseToolset> mcpToolsets = new ArrayList<>();

        /**
         * 原子安全的递进步骤
         */
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /**
         * 当前的智能体
         */
        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (ObjectUtils.isEmpty(agentNames)) {
                return Collections.emptyList();
            }
            List<BaseAgent> agentList = new ArrayList<>();
            for (String agentName : agentNames) {
                BaseAgent agent = agentGroup.get(agentName);
                if (agent != null) {
                    agentList.add(agent);
                }
            }
            return agentList;
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }
    }
}

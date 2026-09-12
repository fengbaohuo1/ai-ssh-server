package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    @Resource
    private CustomRunnerFactory customRunnerFactory;

    @Resource
    private AgentCatalog agentCatalog;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();


        Runner runner = createRunner(dynamicContext, aiAgentConfigTableVO, appName);

        // 子智能体注册中心：把装配完成的 agentGroup 登记进去，供派发链路按 "appName + agentName" 查找
        agentCatalog.register(appName, dynamicContext.getAgentGroup());

        // 模型配置透传到 AiAgentRegisterVO——供意图识别 LLM 分类器复用
        // （模型通道由 ChatModelFactory 统一装配，这里只透传通道 + 三段配置）
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .modelProvider(chatModelConfig != null ? chatModelConfig.getProvider() : null)
                .modelBaseUrl(chatModelConfig != null ? chatModelConfig.getBaseUrl() : null)
                .modelApiKey(chatModelConfig != null ? chatModelConfig.getApiKey() : null)
                .chatModelName(chatModelConfig != null ? chatModelConfig.getModel() : null)
                .build();
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    private Runner createRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);
        // 拓展插件
        List<BasePlugin> basePlugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (ObjectUtils.isNotEmpty(pluginNameList)) {
            basePlugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                basePlugins.add(plugin);
            }
        } else {
            basePlugins = List.of();
        }

        // 统一由 CustomRunnerFactory 装配：自定义 Session/Memory 服务接管框架层会话治理（2-6 节）
        // Session —— 净化器 + 限流器 + 修剪器（剥离动态后缀、截断长文本、限轮次限条数）
        // Memory —— 空实现（收掉 ADK 记忆职责，避免与业务侧记忆重复）
        // W4 压缩：App 承载 EventsCompactionConfig（token 阈值触发时 TailRetentionEventCompactor
        // 把更旧区间交给 DeterministicEventSummarizer 折叠）
        return customRunnerFactory.create(baseAgent, appName, basePlugins);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}

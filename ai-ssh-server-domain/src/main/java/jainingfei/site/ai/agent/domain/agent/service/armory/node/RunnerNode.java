package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventsCompactionConfig;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CustomAdkMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.DeterministicEventSummarizer;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private CustomAdkMemoryService customAdkMemoryService;

    @Resource
    private DeterministicEventSummarizer deterministicEventSummarizer;

    // W4 压缩旋钮（ai.session-governance.compact.*，见 application.yml）
    @Value("${ai.session-governance.compact.enabled:false}")
    private boolean compactEnabled;

    /** 触发压缩的 prompt token 阈值——优先读聚合事件 usageMetadata 的真实 promptTokenCount；
     * 无 usage 事件时框架回退 chars/4 估算（中文场景欠估，按真实 usage 日志校准） */
    @Value("${ai.session-governance.compact.token-threshold:48000}")
    private int compactTokenThreshold;

    /** 近端保留的原始事件条数（其余更旧区间折叠为 [历史摘要]） */
    @Value("${ai.session-governance.compact.event-retention-size:24}")
    private int compactEventRetentionSize;


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

        // 模型配置透传到 AiAgentRegisterVO——供意图识别 LLM 分类器复用
        // （spring-ai 2.x 无 OpenAiApi 类，直接透传 baseUrl/apiKey/model 三要素）
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
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

        // 使用自定义 Session/Memory 服务接管框架层会话治理（2-6 节）：
        // Session —— 净化器 + 限流器 + 修剪器（剥离动态后缀、截断长文本、限轮次限条数）
        // Memory —— 空实现（收掉 ADK 记忆职责，避免与业务侧记忆重复）
        // W4 压缩：Runner.Builder + App 承载 EventsCompactionConfig（token 阈值触发时
        // TailRetentionEventCompactor 把更旧区间交给 DeterministicEventSummarizer 折叠）
        EventsCompactionConfig compactionConfig = buildCompactionConfig();
        App app = App.builder()
                .name(appName)
                .rootAgent(baseAgent)
                .plugins(basePlugins)
                .eventsCompactionConfig(compactionConfig)
                .build();
        return Runner.builder()
                .app(app)
                .artifactService(new InMemoryArtifactService())
                .sessionService(customAdkSessionService)
                .memoryService(customAdkMemoryService)
                .build();
    }

    /** 压缩配置装配（未启用或阈值非法时返回 null = 框架压缩关闭，旧 trim 语义兜底） */
    private EventsCompactionConfig buildCompactionConfig() {
        if (!compactEnabled || compactTokenThreshold <= 0 || compactEventRetentionSize <= 0) {
            return null;
        }
        EventsCompactionConfig config = EventsCompactionConfig.builder()
                .tokenThreshold(compactTokenThreshold)
                .eventRetentionSize(compactEventRetentionSize)
                .summarizer(deterministicEventSummarizer)
                .build();
        log.info("会话压缩已启用: tokenThreshold={} eventRetentionSize={} summarizer={}",
                compactTokenThreshold, compactEventRetentionSize,
                deterministicEventSummarizer.getClass().getSimpleName());
        return config;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}

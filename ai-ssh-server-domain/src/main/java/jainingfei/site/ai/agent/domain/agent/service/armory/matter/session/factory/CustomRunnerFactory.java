package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.factory;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventsCompactionConfig;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CustomAdkMemoryService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.session.DeterministicEventSummarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * Runner 工厂——父智能体与子智能体<b>共用同一套装配方式</b>（会话服务/记忆服务/压缩配置），
 * 只差 appName、rootAgent 与插件列表（教程 2-10 的 CustomRunnerFactory 语义）。
 *
 * <p>复用与隔离的边界：
 * <pre>
 *   复用：CustomAdkSessionService（净化 + 限流 + 修剪 + 压缩）、CustomAdkMemoryService（空实现）
 *         —— 子会话同样被上下文治理约束，不会因为"子智能体"就失去 MAX_EVENTS/MAX_TURNS 限制
 *   隔离：每次派发<b>新建 Runner 实例</b>，appName = 子智能体名，会话 ID 每次新生成
 *         —— 子会话与父会话、子会话与子会话之间互不可见（上下文隔离）
 * </pre>
 */
@Slf4j
@Component
public class CustomRunnerFactory {

    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private CustomAdkMemoryService customAdkMemoryService;

    @Resource
    private DeterministicEventSummarizer deterministicEventSummarizer;

    /** W4 压缩旋钮（ai.session-governance.compact.*，见 application.yml） */
    @Resource
    private SessionGovernanceProperties governance;

    /**
     * 装配一个 Runner。
     *
     * @param rootAgent 根智能体（父智能体或子智能体）
     * @param appName   ADK App 名（父 = 配置表 app-name；子 = 子智能体名）
     * @param plugins   插件列表（父 = runner.plugin-name-list；子 = 空列表）
     */
    public Runner create(BaseAgent rootAgent, String appName, List<BasePlugin> plugins) {
        App app = App.builder()
                .name(appName)
                .rootAgent(rootAgent)
                .plugins(plugins == null ? List.of() : plugins)
                .eventsCompactionConfig(buildCompactionConfig())
                .build();
        return Runner.builder()
                .app(app)
                .artifactService(new InMemoryArtifactService())
                .sessionService(customAdkSessionService)
                .memoryService(customAdkMemoryService)
                .build();
    }

    /**
     * 子智能体 Runner：appName = 子智能体名、不挂父的插件。
     * <p>不挂插件是刻意的——子智能体的可观测由派发工具/执行器的任务级日志承接，
     * 挂上父插件会让同一批日志里父子事件混在一起，反而难排查。
     */
    public Runner createForSubAgent(BaseAgent subAgent) {
        return create(subAgent, subAgent.name(), List.of());
    }

    /** 压缩配置装配（未启用或阈值非法时返回 null = 框架压缩关闭，旧 trim 语义兜底） */
    private EventsCompactionConfig buildCompactionConfig() {
        SessionGovernanceProperties.Compact compact = governance.getCompact();
        if (!compact.isEnabled() || compact.getTokenThreshold() <= 0 || compact.getEventRetentionSize() <= 0) {
            return null;
        }
        EventsCompactionConfig config = EventsCompactionConfig.builder()
                .tokenThreshold(compact.getTokenThreshold())
                .eventRetentionSize(compact.getEventRetentionSize())
                .summarizer(deterministicEventSummarizer)
                .build();
        log.info("会话压缩已启用: tokenThreshold={} eventRetentionSize={} summarizer={}",
                compact.getTokenThreshold(), compact.getEventRetentionSize(),
                deterministicEventSummarizer.getClass().getSimpleName());
        return config;
    }
}

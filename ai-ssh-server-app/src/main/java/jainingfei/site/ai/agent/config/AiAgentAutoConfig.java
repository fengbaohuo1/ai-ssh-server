package jainingfei.site.ai.agent.config;


import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.event.AgentConfigChangedEvent;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.agent.service.IArmoryService;
import jainingfei.site.ai.agent.infrastructure.config.AgentConfigFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Ai Agent 装配触发与运行期热重装。
 *
 * <p><b>启动装配</b>：{@link ApplicationReadyEvent} 时用 Spring 启动期绑定的
 * {@link AiAgentAutoConfigProperties} 装配（外部 agent.yml / llm.yml 已由
 * {@code spring.config.import} 导入）。
 *
 * <p><b>热重装</b>：配置页保存文件后发布 {@link AgentConfigChangedEvent}，本类用
 * {@link AgentConfigFileStore#loadEffectiveProperties()} 重新读取「agent.yml + llm.yml 覆盖层」
 * 的有效配置，刷新配置快照后重新走一遍装配链。装配链本身支持覆盖式替换：
 * <ul>
 *   <li>{@code AbstractArmorySupport#registerBean}：先移除同名 Bean 再注册；</li>
 *   <li>{@code AgentCatalog#register}：按 appName 覆盖登记（注释即写明"便于配置热更新"）；</li>
 *   <li>{@code CustomAdkSessionService}：共享单例 + 内存会话存储，换 Runner 不丢 ADK 会话。</li>
 * </ul>
 * 因此新的对话轮次立即使用新模型/新工具，无需重启进程。
 *
 * <p><b>失败语义</b>：文件已由写接口校验+备份+原子落盘；热重装失败时保留旧装配继续服务，
 * 异常回传给保存接口由前端提示原因。修复后可再次保存，或调用
 * {@code POST /api/v1/agent/config/reload} 手动重装。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({AiAgentAutoConfigProperties.class, SessionGovernanceProperties.class,
        SecurityProperties.class})
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private IArmoryService armoryService;
    @Resource
    private AgentConfigFileStore agentConfigFileStore;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!aiAgentAutoConfigProperties.isEnabled()) {
            log.info("Ai Agent 自动装配未启用，跳过");
            return;
        }
        try {
            logTableSummary(aiAgentAutoConfigProperties.getTables());
            List<AiAgentConfigTableVO> armable = pickArmableTables(aiAgentAutoConfigProperties.getTables());
            if (armable.isEmpty()) {
                log.warn("所有 Ai Agent 配置表均未配置 API Key，跳过装配；应用继续启动，配置页可正常打开，保存后自动热重装");
                return;
            }
            armoryService.acceptArmoryAgents(armable);
        } catch (Exception e) {
            // 空 key / 外部 MCP 不可达 / 配置写错都属于"可在配置页修好"的问题，不应让进程起不来
            log.warn("Ai Agent 自动装配失败，应用继续启动（可在配置页修正配置后保存，自动热重装）: {}", e.getMessage(), e);
        }
    }

    /**
     * 配置变更后热重装（同步执行）。
     *
     * <p>先刷新配置快照再装配：{@code /api/v1/query_ai_agent_config_list}、运行概览等读取同一个
     * {@link AiAgentAutoConfigProperties} Bean，不刷新会一直显示旧配置。
     *
     * @throws IllegalStateException 装配未启用 / 无可装配的 Key / 装配失败（调用方转换为前端提示）
     */
    @EventListener
    public synchronized void onAgentConfigChanged(AgentConfigChangedEvent event) {
        log.info("检测到 Agent 配置变更（source:{}），开始热重装", event.getSource());

        AiAgentAutoConfigProperties fresh = agentConfigFileStore.loadEffectiveProperties();
        Map<String, AiAgentConfigTableVO> tables =
                fresh.getTables() == null ? Map.of() : fresh.getTables();
        aiAgentAutoConfigProperties.setEnabled(fresh.isEnabled());
        aiAgentAutoConfigProperties.setTables(tables);

        if (!fresh.isEnabled()) {
            throw new IllegalStateException("ai.agent.config.enabled=false，已跳过智能体装配");
        }
        logTableSummary(tables);

        List<AiAgentConfigTableVO> armable = pickArmableTables(tables);
        if (armable.isEmpty()) {
            throw new IllegalStateException("配置中没有可装配的智能体（缺少 chat-model.api-key）");
        }
        try {
            armoryService.acceptArmoryAgents(armable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("智能体重装配失败: " + e.getMessage(), e);
        }
        log.info("Ai Agent 热重装完成 | tables:{}（新对话轮次立即生效，无需重启）", armable.size());
    }

    /** 挑出配置了 API Key、可以装配的配置表；未配 key 的表跳过并告警 */
    private static List<AiAgentConfigTableVO> pickArmableTables(Map<String, AiAgentConfigTableVO> tables) {
        List<AiAgentConfigTableVO> armable = new ArrayList<>();
        if (tables == null || tables.isEmpty()) {
            return armable;
        }
        for (Map.Entry<String, AiAgentConfigTableVO> entry : tables.entrySet()) {
            AiAgentConfigTableVO table = entry.getValue();
            if (table == null) {
                continue;
            }
            String apiKey = table.getModule() == null || table.getModule().getChatModel() == null
                    ? null : table.getModule().getChatModel().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Ai Agent 配置表 [{}] 未配置 chat-model.api-key，跳过装配；补全 key 后保存即自动热重装",
                        entry.getKey());
                continue;
            }
            armable.add(table);
        }
        return armable;
    }

    private void logTableSummary(Map<String, AiAgentConfigTableVO> tables) {
        if (tables == null) {
            return;
        }
        tables.forEach((key, table) -> {
            if (table == null || table.getAgent() == null) {
                return;
            }
            String provider = table.getModule() != null && table.getModule().getChatModel() != null
                    ? table.getModule().getChatModel().getProvider() : null;
            String model = table.getModule() != null && table.getModule().getChatModel() != null
                    ? table.getModule().getChatModel().getModel() : null;
            int agentCount = table.getModule() != null && table.getModule().getAgents() != null
                    ? table.getModule().getAgents().size() : 0;
            log.info("Ai Agent 配置加载 | table:{} | appName:{} | agent:{} | provider:{} | model:{} | agents:{}",
                    key, table.getAppName(), table.getAgent().getAgentName(),
                    provider, model, agentCount);
        });
    }

}

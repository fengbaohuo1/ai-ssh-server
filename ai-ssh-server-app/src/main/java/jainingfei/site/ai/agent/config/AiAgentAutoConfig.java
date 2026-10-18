package jainingfei.site.ai.agent.config;


import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.domain.agent.service.IArmoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Ai Agent 装配触发（ApplicationReadyEvent）。
 *
 * <p>装配失败<b>不阻断应用启动</b>：桌面端首次启动时用户还没填 API Key，
 * 此时必须保证进程能起来、配置页能打开（/api/v1/agent/config/*），
 * 保存模型配置并重启后再完成装配。
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

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!aiAgentAutoConfigProperties.isEnabled()) {
            log.info("Ai Agent 自动装配未启用，跳过");
            return;
        }
        try {
            // 只打印装配摘要：绝不能把 chat-model.api-key 等机密写进日志
            logTableSummary();

            List<AiAgentConfigTableVO> armable = new ArrayList<>();
            for (Map.Entry<String, AiAgentConfigTableVO> entry : tables().entrySet()) {
                AiAgentConfigTableVO table = entry.getValue();
                if (table == null) {
                    continue;
                }
                String apiKey = table.getModule() == null || table.getModule().getChatModel() == null
                        ? null : table.getModule().getChatModel().getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("Ai Agent 配置表 [{}] 未配置 chat-model.api-key，跳过装配；"
                            + "保存模型配置并重启后自动启用", entry.getKey());
                    continue;
                }
                armable.add(table);
            }

            if (armable.isEmpty()) {
                log.warn("所有 Ai Agent 配置表均未配置 API Key，跳过装配；应用继续启动，配置页可正常打开");
                return;
            }
            armoryService.acceptArmoryAgents(armable);
        } catch (Exception e) {
            // 空 key / 外部 MCP 不可达 / 配置写错都属于"可在配置页修好"的问题，不应让进程起不来
            log.warn("Ai Agent 自动装配失败，应用继续启动（可在配置页修正配置后重启）: {}", e.getMessage(), e);
        }
    }

    private Map<String, AiAgentConfigTableVO> tables() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        return tables == null ? Map.of() : tables;
    }

    private void logTableSummary() {
        if (aiAgentAutoConfigProperties.getTables() == null) {
            return;
        }
        aiAgentAutoConfigProperties.getTables().forEach((key, table) -> {
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

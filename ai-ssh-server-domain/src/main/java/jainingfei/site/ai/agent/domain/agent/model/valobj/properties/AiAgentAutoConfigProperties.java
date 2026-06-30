package jainingfei.site.ai.agent.domain.agent.model.valobj.properties;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * AI Agent 自动配置属性，映射 application.yml 中 ai.agent 下的配置。
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    private Map<String, AiAgentConfigTableVO> tables;

}

package jainingfei.site.ai.agent.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AiAgentRegisterVO {
    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 智能体执行对象
     */
    private Runner runner;

    /**
     * 模型 API baseUrl（意图识别 LLM 分类器复用，2-7 节新增）
     */
    private String modelBaseUrl;

    /**
     * 模型 API key（意图识别 LLM 分类器复用，2-7 节新增）
     */
    private String modelApiKey;

    /**
     * 模型名称（意图识别 LLM 分类器复用，2-7 节新增）
     */
    private String chatModelName;
}

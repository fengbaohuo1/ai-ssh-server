package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选模型通道（配置页下拉框数据源）。
 */
@Data
public class AgentProviderOptionDTO {

    /** 通道 id（写入 chat-model.provider） */
    private String id;

    /** 展示名 */
    private String displayName;

    /** 该通道可接受的推理强度取值（空 = 不声明） */
    private List<String> reasoningEffortValues = new ArrayList<>();

    /** 一句人话说明 */
    private String note;
}

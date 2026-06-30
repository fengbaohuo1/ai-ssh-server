package jainingfei.site.ai.agent.domain.agent.model.entity;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ArmoryCommandEntity {
    private AiAgentConfigTableVO aiAgentConfigTableVO;
}

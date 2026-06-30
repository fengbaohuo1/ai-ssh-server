package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

@Data
public class ChatResponseDTO {

    private String content;
    /** Agent 动作步骤（思考、工具调用、工具返回） */
    private String steps;

}

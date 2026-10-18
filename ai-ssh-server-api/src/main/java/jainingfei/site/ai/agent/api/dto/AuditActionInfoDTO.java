package jainingfei.site.ai.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计动作元数据 DTO——供前端筛选器渲染中文名与风险色。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditActionInfoDTO {

    /** 动作枚举名 */
    private String name;

    /** 中文名 */
    private String label;

    /** 默认风险等级 */
    private String riskLevel;

    /** 风险中文名 */
    private String riskLabel;
}

package jainingfei.site.ai.agent.domain.security.model.valobj.enums;

/**
 * 审计风险等级——前端据此着色，运维据此筛选高危操作。
 */
public enum RiskLevelEnum {

    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("严重");

    private final String label;

    RiskLevelEnum(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static RiskLevelEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return LOW;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOW;
        }
    }
}

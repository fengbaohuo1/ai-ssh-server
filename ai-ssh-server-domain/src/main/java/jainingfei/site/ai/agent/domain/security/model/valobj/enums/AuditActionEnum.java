package jainingfei.site.ai.agent.domain.security.model.valobj.enums;

/**
 * 审计动作分类——覆盖连接生命周期、命令执行、文件操作与 AI 会话。
 */
public enum AuditActionEnum {

    CONNECTION_CREATE("连接创建", RiskLevelEnum.MEDIUM),
    CONNECTION_UPDATE("连接修改", RiskLevelEnum.MEDIUM),
    CONNECTION_DELETE("连接删除", RiskLevelEnum.HIGH),
    CONNECTION_CONNECT("建立连接", RiskLevelEnum.LOW),
    CONNECTION_DISCONNECT("断开连接", RiskLevelEnum.LOW),

    COMMAND_EXECUTE("命令执行", RiskLevelEnum.HIGH),

    FILE_LIST("目录浏览", RiskLevelEnum.LOW),
    FILE_PREVIEW("文件预览", RiskLevelEnum.LOW),
    FILE_UPLOAD("文件上传", RiskLevelEnum.HIGH),
    FILE_DOWNLOAD("文件下载", RiskLevelEnum.MEDIUM),
    FILE_DELETE("文件删除", RiskLevelEnum.CRITICAL),
    FILE_RENAME("文件重命名", RiskLevelEnum.MEDIUM),
    FILE_MOVE("文件移动", RiskLevelEnum.HIGH),
    FILE_MKDIR("创建目录", RiskLevelEnum.LOW),
    FILE_WRITE("文件写入", RiskLevelEnum.HIGH),

    AI_CHAT("AI 对话", RiskLevelEnum.LOW);

    private final String label;
    private final RiskLevelEnum defaultRisk;

    AuditActionEnum(String label, RiskLevelEnum defaultRisk) {
        this.label = label;
        this.defaultRisk = defaultRisk;
    }

    public String getLabel() {
        return label;
    }

    public RiskLevelEnum getDefaultRisk() {
        return defaultRisk;
    }
}

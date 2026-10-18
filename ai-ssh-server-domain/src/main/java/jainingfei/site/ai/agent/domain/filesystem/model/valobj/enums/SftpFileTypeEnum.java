package jainingfei.site.ai.agent.domain.filesystem.model.valobj.enums;

/**
 * 远程文件类型——与 SFTP 属性一一对应，前端按此渲染图标与操作。
 */
public enum SftpFileTypeEnum {

    FILE("file"),
    DIRECTORY("dir"),
    LINK("link"),
    OTHER("other");

    private final String code;

    SftpFileTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

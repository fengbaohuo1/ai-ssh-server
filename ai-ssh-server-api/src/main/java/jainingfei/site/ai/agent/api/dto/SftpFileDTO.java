package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * 远程文件条目 DTO（与领域 SftpFileVO 一一对应）。
 */
@Data
public class SftpFileDTO {

    /** 文件名 */
    private String name;

    /** 完整路径 */
    private String path;

    /** 类型: file / dir / link / other */
    private String type;

    /** 字节数 */
    private long size;

    /** 修改时间（epoch 毫秒） */
    private long modifiedAt;

    /** Unix 权限位 */
    private int permissions;

    /** 权限文本 */
    private String permissionsText;

    /** 是否隐藏文件 */
    private boolean hidden;

    /** 软链接目标 */
    private String linkTarget;

    /** 所属用户 */
    private String owner;
}

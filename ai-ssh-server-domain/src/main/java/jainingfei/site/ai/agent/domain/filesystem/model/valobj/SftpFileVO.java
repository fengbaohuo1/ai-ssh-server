package jainingfei.site.ai.agent.domain.filesystem.model.valobj;

import jainingfei.site.ai.agent.domain.filesystem.model.valobj.enums.SftpFileTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 远程文件值对象——一次目录列表或 stat 的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpFileVO {

    /** 文件名（不含父路径） */
    private String name;

    /** 规范化后的绝对/相对路径 */
    private String path;

    /** 类型 */
    private SftpFileTypeEnum type;

    /** 字节数（目录为 0） */
    private long size;

    /** 修改时间（epoch 毫秒） */
    private long modifiedAt;

    /** Unix 权限位（如 0755） */
    private int permissions;

    /** 权限文本（如 drwxr-xr-x） */
    private String permissionsText;

    /** 是否隐藏文件（以 . 开头） */
    private boolean hidden;

    /** 软链接目标（仅 type=LINK 时可能非空） */
    private String linkTarget;

    /** 所属用户 / 组（来自 longname，尽力而为） */
    private String owner;
}

package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * SFTP 写操作请求 DTO（mkdir / rename / delete / write 复用）。
 */
@Data
public class SftpOperationRequestDTO {

    /** 目标路径 */
    private String path;

    /** 新路径（rename） */
    private String newPath;

    /** 是否递归删除（delete） */
    private boolean recursive;

    /** 文本内容（write） */
    private String content;
}

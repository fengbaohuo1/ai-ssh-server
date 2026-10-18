package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * 文本预览响应 DTO。
 */
@Data
public class SftpPreviewResponseDTO {

    /** 文件路径 */
    private String path;

    /** 文本内容（binary=true 时为空） */
    private String content;

    /** 文件实际大小 */
    private long size;

    /** 是否被预览上限截断 */
    private boolean truncated;

    /** 是否二进制文件 */
    private boolean binary;

    /** 编码 */
    private String encoding;
}

package jainingfei.site.ai.agent.domain.filesystem.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本预览值对象——二进制文件不返回内容，只标记类型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpPreviewVO {

    /** 文件路径 */
    private String path;

    /** 文本内容（binary=true 时为空） */
    private String content;

    /** 文件实际大小（字节） */
    private long size;

    /** 是否因预览上限被截断 */
    private boolean truncated;

    /** 是否二进制文件（含 NUL 字节或非法 UTF-8） */
    private boolean binary;

    /** 编码（当前固定 UTF-8） */
    private String encoding;
}

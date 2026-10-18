package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 目录列表响应 DTO。
 */
@Data
public class SftpListResponseDTO {

    /** 规范化目录路径 */
    private String path;

    /** 父目录（根目录为 null） */
    private String parent;

    /** 条目 */
    private List<SftpFileDTO> files;

    /** 条目总数 */
    private int total;

    /** 是否因上限被截断 */
    private boolean truncated;
}

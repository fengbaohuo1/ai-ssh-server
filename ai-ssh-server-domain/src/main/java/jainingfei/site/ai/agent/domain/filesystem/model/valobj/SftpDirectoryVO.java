package jainingfei.site.ai.agent.domain.filesystem.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 目录列表值对象——路径、父路径与条目集合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpDirectoryVO {

    /** 规范化后的目录路径 */
    private String path;

    /** 父目录（根目录为 null） */
    private String parent;

    /** 条目（目录优先、名称升序） */
    private List<SftpFileVO> files;

    /** 条目总数 */
    private int total;

    /** 是否因条目上限被截断（true 时列表只含前 maxListEntries 项） */
    private boolean truncated;
}

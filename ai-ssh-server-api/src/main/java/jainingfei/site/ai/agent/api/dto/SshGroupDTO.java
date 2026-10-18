package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * SSH 连接分组统计 DTO。
 */
@Data
public class SshGroupDTO {

    /** 分组名（空分组归一为 "未分组"） */
    private String name;

    /** 该分组下的连接数 */
    private long count;

    /** 其中已收藏数量 */
    private long favoriteCount;
}

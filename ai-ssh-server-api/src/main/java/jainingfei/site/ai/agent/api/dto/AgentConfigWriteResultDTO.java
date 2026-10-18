package jainingfei.site.ai.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配置写入结果——配置文件在启动期被 Spring 读取，保存后需重启进程才生效。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfigWriteResultDTO {

    /** 是否需要重启后生效（当前恒为 true） */
    private boolean restartRequired;
}

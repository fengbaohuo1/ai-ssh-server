package jainingfei.site.ai.agent.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 终端会话实体 — SSH ChannelShell 会话的领域抽象。
 * <p>
 * 一个终端会话绑定到一个已建立的 SSH 连接（connectionId），
 * 提供 Shell 命令的读写能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminalSessionEntity {

    /** 终端会话唯一标识 */
    private String sessionId;

    /** 所属 SSH 连接 ID */
    private String connectionId;

    /** 终端列数 */
    private Integer cols;

    /** 终端行数 */
    private Integer rows;

    /** 会话状态：0-关闭, 1-活跃 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveAt;

    public boolean isActive() {
        return status != null && status == 1;
    }

    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }
}

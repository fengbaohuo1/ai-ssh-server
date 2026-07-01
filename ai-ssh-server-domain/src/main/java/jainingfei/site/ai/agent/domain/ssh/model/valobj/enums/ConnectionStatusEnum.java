package jainingfei.site.ai.agent.domain.ssh.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 连接状态枚举
 */
@Getter
@AllArgsConstructor
public enum ConnectionStatusEnum {

    DISCONNECTED(0, "未连接"),
    CONNECTED(1, "已连接"),
    CONNECTING(2, "连接中"),
    FAILED(3, "连接失败"),
    ;

    private final Integer code;
    private final String desc;

    public static ConnectionStatusEnum fromCode(Integer code) {
        if (code == null) return null;
        for (ConnectionStatusEnum value : values()) {
            if (value.code.equals(code)) return value;
        }
        return null;
    }
}

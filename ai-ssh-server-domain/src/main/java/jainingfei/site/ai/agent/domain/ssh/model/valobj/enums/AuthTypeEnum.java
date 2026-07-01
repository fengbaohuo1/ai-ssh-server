package jainingfei.site.ai.agent.domain.ssh.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 认证类型枚举
 */
@Getter
@AllArgsConstructor
public enum AuthTypeEnum {

    PASSWORD(1, "密码"),
    PRIVATE_KEY(2, "私钥"),
    ;

    private final Integer code;
    private final String desc;

    public static AuthTypeEnum fromCode(Integer code) {
        if (code == null) return null;
        for (AuthTypeEnum value : values()) {
            if (value.code.equals(code)) return value;
        }
        return null;
    }
}

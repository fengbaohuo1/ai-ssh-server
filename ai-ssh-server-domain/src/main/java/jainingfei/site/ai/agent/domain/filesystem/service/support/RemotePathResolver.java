package jainingfei.site.ai.agent.domain.filesystem.service.support;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * 远程路径解析器——所有 SFTP 操作进入基础设施前的<b>唯一</b>路径闸门。
 *
 * <p>防护目标：
 * <ul>
 *   <li>拒绝 NUL / 控制字符 / 超长路径</li>
 *   <li>词法规范化 {@code //}、{@code .}、{@code ..}，并拒绝越出根目录的 {@code ..}</li>
 *   <li>拒绝配置的敏感目录前缀（默认 /proc、/sys、/dev）</li>
 *   <li>文件名（上传）只能是 basename，不能借文件名逃逸目录</li>
 * </ul>
 *
 * <p>注意：这是<b>词法</b>防护，不是 realpath。符号链接仍可能指向敏感目录，
 * 因此敏感目录拦截也覆盖"目标路径落在敏感前缀下"的情况；真正的最终防线是远端账号权限。
 */
@Component
public class RemotePathResolver {

    private static final int MAX_PATH_LENGTH = 4096;

    @Resource
    private SecurityProperties securityProperties;

    /**
     * 规范化并校验路径。
     *
     * @param rawPath 原始路径（null / 空 → 当前目录 "."）
     * @return 规范化路径（绝对路径以 / 开头；相对路径保持相对）
     */
    public String resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return ".";
        }
        String path = rawPath.trim().replace('\\', '/');

        if (path.length() > MAX_PATH_LENGTH) {
            throw badPath("路径过长（上限 " + MAX_PATH_LENGTH + " 字符）");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\u0000' || Character.isISOControl(c)) {
                throw badPath("路径包含非法控制字符");
            }
        }

        boolean absolute = path.startsWith("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    throw badPath("路径越界：不能通过 .. 逃出根目录");
                }
                stack.removeLast();
                continue;
            }
            stack.addLast(segment);
        }

        String normalized = String.join("/", stack);
        if (absolute) {
            normalized = "/" + normalized;
        }
        if (normalized.isEmpty()) {
            normalized = absolute ? "/" : ".";
        }
        if (!".".equals(normalized)) {
            assertNotBlocked(normalized);
        }
        return normalized;
    }

    /**
     * 把用户上传的文件名安全地拼到目录下（文件名只能是 basename）。
     */
    public String resolveChild(String directory, String fileName) {
        String safeName = sanitizeFileName(fileName);
        String dir = resolve(directory);
        if (".".equals(dir)) {
            return safeName;
        }
        return resolve(dir.endsWith("/") ? dir + safeName : dir + "/" + safeName);
    }

    /** 仅保留 basename，拒绝路径分隔符与保留名 */
    public String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw badPath("文件名不能为空");
        }
        String name = fileName.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw badPath("非法文件名");
        }
        if (name.length() > 255) {
            throw badPath("文件名过长（上限 255 字符）");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw badPath("文件名包含非法控制字符");
            }
        }
        return name;
    }

    /** 父目录路径；根目录返回 null */
    public String parentOf(String path) {
        String normalized = resolve(path);
        if ("/".equals(normalized) || ".".equals(normalized)) {
            return null;
        }
        int idx = normalized.lastIndexOf('/');
        if (idx < 0) {
            return ".";
        }
        return idx == 0 ? "/" : normalized.substring(0, idx);
    }

    /** 文件名 */
    public String fileNameOf(String path) {
        String normalized = resolve(path);
        if ("/".equals(normalized) || ".".equals(normalized)) {
            return normalized;
        }
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    /** 是否为根目录（禁止删除 / 重命名）。"." 视为用户主目录根，同样禁止。 */
    public boolean isRoot(String path) {
        String normalized = resolve(path);
        return "/".equals(normalized) || ".".equals(normalized);
    }

    /**
     * 判断 {@code path} 是否等于 {@code ancestor} 或位于其下。
     *
     * <p>用于移动/重命名防护：不能把目录移动到自身或自己的子目录里。
     * 相对路径与绝对路径分别比较，避免把两个语义空间混在一起。
     */
    public boolean isSameOrDescendant(String ancestor, String path) {
        String a = resolve(ancestor);
        String p = resolve(path);
        if (a.equals(p)) {
            return true;
        }
        if ("/".equals(a)) {
            return p.startsWith("/");
        }
        if (".".equals(a)) {
            return !p.startsWith("/");
        }
        return p.startsWith(a + "/");
    }

    /**
     * 敏感目录校验：等于前缀或位于前缀之下都拒绝。
     */
    private void assertNotBlocked(String normalizedPath) {
        if (!normalizedPath.startsWith("/")) {
            return;
        }
        for (String blocked : securityProperties.getSftp().getBlockedPaths()) {
            if (blocked == null || blocked.isBlank()) {
                continue;
            }
            String prefix = blocked.startsWith("/") ? blocked : "/" + blocked;
            if (prefix.length() > 1 && prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/")) {
                throw badPath("禁止访问系统目录：" + prefix);
            }
        }
    }

    private static AppException badPath(String message) {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
    }
}

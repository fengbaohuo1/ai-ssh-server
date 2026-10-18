package jainingfei.site.ai.agent.test.domain.filesystem;

import jainingfei.site.ai.agent.domain.filesystem.service.support.RemotePathResolver;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远程路径闸门单元测试——目录穿越、控制字符、敏感目录与文件名逃逸。
 */
public class RemotePathResolverTest {

    private RemotePathResolver resolver;

    @BeforeEach
    public void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.getSftp().setBlockedPaths(List.of("/proc", "/sys", "/dev"));
        resolver = new RemotePathResolver();
        ReflectionTestUtils.setField(resolver, "securityProperties", properties);
    }

    @Test
    @DisplayName("空路径归一为当前目录，重复斜杠与 . 被折叠")
    public void test_normalization() {
        assertEquals(".", resolver.resolve(null));
        assertEquals(".", resolver.resolve("   "));
        assertEquals("/etc/nginx/nginx.conf", resolver.resolve("//etc//nginx/./nginx.conf"));
        assertEquals("/", resolver.resolve("/"));
        assertEquals("/var/log", resolver.resolve("/var/log/../log"));
    }

    @Test
    @DisplayName("相对路径保留相对语义")
    public void test_relative_path() {
        assertEquals(".", resolver.resolve("."));
        assertEquals("nginx.conf", resolver.resolve("./nginx.conf"));
        assertEquals("conf.d/site.conf", resolver.resolve("conf.d/./site.conf"));
    }

    @Test
    @DisplayName("越界 .. 被拒绝：不能从根目录或相对路径向上逃逸")
    public void test_traversal_rejected() {
        assertThrows(AppException.class, () -> resolver.resolve("/../etc/passwd"));
        assertThrows(AppException.class, () -> resolver.resolve("../../etc/passwd"));
        assertThrows(AppException.class, () -> resolver.resolve("/var/../../etc"));
    }

    @Test
    @DisplayName("敏感目录前缀被拒绝，同名前缀不误伤")
    public void test_blocked_paths() {
        assertThrows(AppException.class, () -> resolver.resolve("/proc/1/cmdline"));
        assertThrows(AppException.class, () -> resolver.resolve("/sys/kernel"));
        assertThrows(AppException.class, () -> resolver.resolve("/dev/sda"));
        assertEquals("/process/list", resolver.resolve("/process/list"), "/proc 前缀不得误伤 /process");
    }

    @Test
    @DisplayName("控制字符与超长路径被拒绝")
    public void test_illegal_characters() {
        assertThrows(AppException.class, () -> resolver.resolve("/tmp/a\u0000b"));
        assertThrows(AppException.class, () -> resolver.resolve("/tmp/line\nbreak"));
        assertThrows(AppException.class, () -> resolver.resolve("/" + "a".repeat(5000)));
    }

    @Test
    @DisplayName("上传文件名只能保留 basename，不能借文件名逃逸目录")
    public void test_resolve_child_strips_path() {
        // 目录部分被剥离，文件仍落在目标目录内（不会写到 /etc/passwd）
        assertEquals("/var/log/passwd", resolver.resolveChild("/var/log", "../../etc/passwd"));
        assertEquals("/var/log/app.log", resolver.resolveChild("/var/log", "app.log"));
        assertEquals("app.log", resolver.resolveChild(".", "app.log"));
        assertThrows(AppException.class, () -> resolver.resolveChild("/var/log", ".."));
        assertThrows(AppException.class, () -> resolver.resolveChild("/var/log", "   "));
    }

    @Test
    @DisplayName("父目录与根目录判定")
    public void test_parent_and_root() {
        assertEquals("/etc/nginx", resolver.parentOf("/etc/nginx/nginx.conf"));
        assertEquals("/etc", resolver.parentOf("/etc/nginx"));
        assertEquals("/", resolver.parentOf("/etc"));
        assertNull(resolver.parentOf("/"));
        assertNull(resolver.parentOf("."));
        assertTrue(resolver.isRoot("/"));
        assertTrue(resolver.isRoot("/var/.."));
        assertTrue(resolver.isRoot("."), "当前目录（用户主目录）同样禁止删除/重命名");
        assertEquals("nginx.conf", resolver.fileNameOf("/etc/nginx/nginx.conf"));
    }

    @Test
    @DisplayName("移动防护：自身/子目录判定，前缀相同但非子目录不误伤")
    public void test_same_or_descendant() {
        assertTrue(resolver.isSameOrDescendant("/etc", "/etc"));
        assertTrue(resolver.isSameOrDescendant("/etc", "/etc/nginx"));
        assertTrue(resolver.isSameOrDescendant("/etc/nginx", "/etc/nginx/conf.d"));
        assertTrue(resolver.isSameOrDescendant(".", "a/b"));
        assertTrue(resolver.isSameOrDescendant("/", "/var/log"));

        assertFalse(resolver.isSameOrDescendant("/etc", "/etcd"));
        assertFalse(resolver.isSameOrDescendant("/etc", "/var/etc"));
        assertFalse(resolver.isSameOrDescendant(".", "/var/log"));
        assertFalse(resolver.isSameOrDescendant("/var/log", "nginx"));
    }
}

package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.impl.TerminalStateProvider;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.SshCommandResult;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TerminalStateProvider 回合级环境缓存单测——纯 JVM，不依赖 Spring 容器与真实 SSH。
 *
 * <p>验证点：
 * <ul>
 *   <li>TTL 窗口内多次组装上下文只采样一次（3 条命令）；</li>
 *   <li>{@code invalidate} 后强制重新采样；</li>
 *   <li>未绑定连接不触发任何 SSH 调用；</li>
 *   <li>过期后重新采样；</li>
 *   <li>调用方修改返回 Map 不会污染缓存。</li>
 * </ul>
 */
class TerminalStateProviderTest {

    @Test
    void test_same_turn_assemblies_reuse_cached_state() {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = provider(terminal);

        Map<String, Object> first = provider.provide("s1", "u1", "conn-1", List.of());
        Map<String, Object> second = provider.provide("s1", "u1", "conn-1", List.of());
        Map<String, Object> third = provider.provide("s2", "u2", "conn-1", List.of());

        assertEquals(3, terminal.commands.size(), "TTL 内只采样一次（3 条命令）");
        assertEquals("Linux 5.10", first.get("osInfo"));
        assertEquals("Linux 5.10", third.get("osInfo"));
        assertEquals(first, second);
    }

    @Test
    void test_invalidate_forces_recollect() {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = provider(terminal);

        provider.provide("s1", "u1", "conn-1", List.of());
        provider.invalidate("conn-1");
        provider.provide("s1", "u1", "conn-1", List.of());

        assertEquals(6, terminal.commands.size(), "失效后应重新采样");
    }

    @Test
    void test_blank_terminal_id_skips_ssh() {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = provider(terminal);

        assertTrue(provider.provide("s1", "u1", null, List.of()).isEmpty());
        assertTrue(provider.provide("s1", "u1", "", List.of()).isEmpty());
        assertEquals(0, terminal.commands.size());
        assertEquals(0, provider.cachedConnections());
    }

    @Test
    void test_cache_expires_after_ttl() throws Exception {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = new TerminalStateProvider() {
            @Override
            protected long cacheTtlMs() {
                return 50L;
            }
        };
        ReflectionTestUtils.setField(provider, "sshTerminalService", terminal);

        provider.provide("s1", "u1", "conn-1", List.of());
        Thread.sleep(80L);
        provider.provide("s1", "u1", "conn-1", List.of());

        assertEquals(6, terminal.commands.size(), "TTL 过期后应重新采样");
    }

    @Test
    void test_returned_map_is_copy() {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = provider(terminal);

        Map<String, Object> first = provider.provide("s1", "u1", "conn-1", List.of());
        first.put("osInfo", "tampered");

        Map<String, Object> second = provider.provide("s1", "u1", "conn-1", List.of());
        assertEquals("Linux 5.10", second.get("osInfo"));
    }

    @Test
    void test_invalidate_blank_is_noop() {
        CountingTerminal terminal = new CountingTerminal();
        TerminalStateProvider provider = provider(terminal);
        provider.provide("s1", "u1", "conn-1", List.of());

        provider.invalidate(null);
        provider.invalidate("");

        assertEquals(1, provider.cachedConnections());
    }

    private TerminalStateProvider provider(ISshTerminalService terminal) {
        TerminalStateProvider provider = new TerminalStateProvider();
        ReflectionTestUtils.setField(provider, "sshTerminalService", terminal);
        return provider;
    }

    /** 记录调用命令的假终端服务（只用到 execCommand）。 */
    private static class CountingTerminal implements ISshTerminalService {
        private final List<String> commands = new ArrayList<>();
        private final Map<String, String> outputs = new HashMap<>(Map.of(
                "uname -srm", "Linux 5.10",
                "whoami", "root",
                "pwd", "/root"));

        @Override
        public String executeCommand(String connectionId, String command) {
            return execCommand(connectionId, command);
        }

        @Override
        public String execCommand(String connectionId, String command) {
            commands.add(command);
            String output = outputs.get(command);
            assertFalse(output == null, "unexpected command: " + command);
            return output;
        }

        @Override
        public SshCommandResult execCommandDetailed(String connectionId, String command) {
            return SshCommandResult.builder()
                    .stdout(execCommand(connectionId, command))
                    .stderr("")
                    .exitStatus(0)
                    .build();
        }

        @Override
        public boolean sessionExists(String connectionId) {
            return true;
        }

        @Override
        public void closeTerminal(String connectionId) {
            // no-op
        }
    }
}

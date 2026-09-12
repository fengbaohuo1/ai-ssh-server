package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.agents.BaseAgent;
import jainingfei.site.ai.agent.domain.agent.service.armory.catalog.AgentCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体注册中心（AgentCatalog）单元测试——纯内存。
 *
 * <p>覆盖：按 appName + agentName 精确查找、跨表全局兜底、未注册返回空、list 快照与覆盖式注册。
 * <p>注：教程以数字 agentId 登记、以 ADK agentName 查找，两者键不一致导致精确查找永远 miss；
 * 这里用 appName 作键，首次查找即命中。
 */
public class AgentCatalogTest {

    private StubSubAgent agent(String name) {
        return StubSubAgent.replying(name, "ok");
    }

    @Test
    public void test_register_and_find_exact() {
        AgentCatalog catalog = new AgentCatalog();
        BaseAgent diagnosis = agent("sshDiagnosisAgent");
        catalog.register("sshAgent", Map.of("sshDiagnosisAgent", diagnosis, "sshOperator", agent("sshOperator")));

        assertTrue(catalog.find("sshAgent", "sshDiagnosisAgent").isPresent());
        assertSame(diagnosis, catalog.find("sshAgent", "sshDiagnosisAgent").get());
    }

    @Test
    public void test_find_miss_returns_empty_and_fallback_by_name() {
        AgentCatalog catalog = new AgentCatalog();
        catalog.register("sshAgent", Map.of("sshDiagnosisAgent", agent("sshDiagnosisAgent")));

        // 换一个 appName：精确查找 miss，但全局兜底能命中（容错路径）
        assertFalse(catalog.find("otherAgent", "sshDiagnosisAgent").isPresent());
        assertTrue(catalog.findByName("sshDiagnosisAgent").isPresent());
        assertFalse(catalog.findByName("ghostAgent").isPresent());
    }

    @Test
    public void test_list_and_re_register() {
        AgentCatalog catalog = new AgentCatalog();
        catalog.register("sshAgent", Map.of("a", agent("a")));
        assertEquals(1, catalog.list("sshAgent").size());

        // 重复装配覆盖式替换（配置热更新语义）
        catalog.register("sshAgent", Map.of("a", agent("a"), "b", agent("b")));
        assertEquals(2, catalog.list("sshAgent").size());

        assertTrue(catalog.list("unknownApp").isEmpty());
    }
}

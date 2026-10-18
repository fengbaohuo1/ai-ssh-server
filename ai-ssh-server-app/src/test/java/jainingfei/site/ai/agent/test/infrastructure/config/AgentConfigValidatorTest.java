package jainingfei.site.ai.agent.test.infrastructure.config;

import jainingfei.site.ai.agent.infrastructure.config.AgentConfigFileStore;
import jainingfei.site.ai.agent.infrastructure.config.AgentConfigValidationException;
import jainingfei.site.ai.agent.infrastructure.config.AgentConfigValidator;
import jainingfei.site.ai.agent.infrastructure.config.AiSshHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置保存前置校验器单元测试——纯 JVM，不启动 Spring、不连数据库。
 *
 * <p>覆盖：
 * <ul>
 *   <li>合法配置通过；</li>
 *   <li>重名智能体 / 悬空 sub-agents 引用 / 非法 delegate-mode / 空 provider·model /
 *       runner 引用不存在 —— 各自报错，且 {@link AgentConfigFileStore#writeAgentContent} 拒绝落盘
 *       （磁盘原文件不变、不产生 .bak 备份）；</li>
 *   <li>provider / model 允许回退 llm.yml 覆盖层（老配置必须能保存）；</li>
 *   <li>合法配置正常写入并保留原有备份行为。</li>
 * </ul>
 */
public class AgentConfigValidatorTest {

    /** 一份结构完整、语义合法的 agent.yml（校验应通过） */
    private static final String VALID_YAML = """
            ai:
              agent:
                config:
                  enabled: true
                  tables:
                    sshAgent:
                      app-name: sshAgent
                      agent:
                        agent-id: 100004
                        agent-name: SSH AI Agent
                        agent-desc: test
                      module:
                        chat-model:
                          provider: deepseek-native
                          base-url: https://api.deepseek.com
                          api-key: sk-test
                          model: deepseek-flash
                          tool-mcp-list:
                            - remote:
                                name: llm-gateway
                                url: http://127.0.0.1:8790/mcp
                        agents:
                          - name: sshOperator
                            instruction: operate
                            delegate-mode: hybrid
                            sub-agents:
                              - sshDiagnosisAgent
                          - name: sshDiagnosisAgent
                            instruction: read-only diagnose
                            read-only: true
                        runner:
                          agent-name: sshOperator
            """;

    /** 磁盘上的"原文件"：校验失败时必须一字不改 */
    private static final String ORIGINAL_YAML =
            "ai:\n  agent:\n    config:\n      enabled: true\n      tables:\n        keep: {}\n";

    private String previousHome;
    private Path home;
    private boolean isolatedHome;

    @BeforeEach
    public void setUp() throws IOException {
        // AiSshHome 解析优先级：环境变量 > 系统属性；环境变量存在时进程内无法覆盖，
        // 此场景下只跑纯校验用例，涉及写盘的用例自动跳过（避免误改真实用户配置）。
        String envHome = System.getenv(AiSshHome.HOME_PROPERTY);
        isolatedHome = envHome == null || envHome.isBlank();
        previousHome = System.getProperty(AiSshHome.HOME_PROPERTY);
        home = Files.createTempDirectory("ai-ssh-validator-test-");
        System.setProperty(AiSshHome.HOME_PROPERTY, home.toString());
        Files.createDirectories(home.resolve("config"));
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (previousHome == null) {
            System.clearProperty(AiSshHome.HOME_PROPERTY);
        } else {
            System.setProperty(AiSshHome.HOME_PROPERTY, previousHome);
        }
        if (home == null || !Files.exists(home)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(home)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结论
                }
            });
        }
    }

    // ==================== 校验器：合法 / 各类非法 ====================

    @Test
    public void test_valid_config_passes() {
        List<String> errors = AgentConfigValidator.validate(parse(VALID_YAML));
        assertTrue(errors.isEmpty(), () -> "合法配置不应报错，实际: " + errors);
    }

    @Test
    public void test_duplicate_agent_name_rejected() throws IOException {
        String candidate = VALID_YAML.replace("sshDiagnosisAgent", "sshOperator");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "重复");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_dangling_sub_agent_rejected() throws IOException {
        String candidate = VALID_YAML.replace("- sshDiagnosisAgent", "- ghostAgent");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "sub-agents 引用了不存在的智能体");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_unknown_delegate_mode_rejected() throws IOException {
        String candidate = VALID_YAML.replace("delegate-mode: hybrid", "delegate-mode: broadcast");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "delegate-mode 非法");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_blank_provider_and_model_rejected() throws IOException {
        String candidate = withoutProviderAndModel();
        List<String> errors = AgentConfigValidator.validate(parse(candidate));
        assertContains(errors, "chat-model.provider 不能为空");
        assertContains(errors, "chat-model.model 不能为空");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_unknown_provider_rejected() throws IOException {
        String candidate = VALID_YAML.replace("provider: deepseek-native", "provider: some-vendor");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "未知模型通道");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_runner_reference_missing_rejected() throws IOException {
        String candidate = VALID_YAML.replace("agent-name: sshOperator", "agent-name: ghostRunner");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "runner.agent-name 引用了不存在");
        assertRejectedWithoutWrite(candidate);
    }

    @Test
    public void test_tool_mcp_entry_without_url_rejected() throws IOException {
        String candidate = VALID_YAML.replace("url: http://127.0.0.1:8790/mcp", "url:");
        assertContains(AgentConfigValidator.validate(parse(candidate)), "remote.url 不能为空");
        assertRejectedWithoutWrite(candidate);
    }

    // ==================== 覆盖层回退 ====================

    @Test
    public void test_provider_and_model_fallback_to_overlay() {
        String candidate = withoutProviderAndModel();
        // 纯校验器：显式传入覆盖层回退值 → 通过
        List<String> errors = AgentConfigValidator.validate(parse(candidate),
                Map.of("sshAgent", new AgentConfigValidator.OverlayChatModel("openai-compatible", "deepseek-chat")));
        assertTrue(errors.isEmpty(), () -> "覆盖层已提供 provider/model 时应通过，实际: " + errors);
    }

    @Test
    public void test_provider_and_model_fallback_to_overlay_file() throws IOException {
        assumeIsolatedHome();
        Files.writeString(home.resolve("config").resolve("llm.yml"), """
                ai:
                  agent:
                    config:
                      tables:
                        sshAgent:
                          module:
                            chat-model:
                              provider: openai-compatible
                              model: deepseek-chat
                """, StandardCharsets.UTF_8);
        String candidate = withoutProviderAndModel();
        seedAgentFile(ORIGINAL_YAML);

        store().writeAgentContent(candidate);

        assertEquals(candidate, Files.readString(agentFile(), StandardCharsets.UTF_8),
                "覆盖层已提供 provider/model 时，老配置（agent.yml 不写这两项）必须能保存");
    }

    // ==================== 落盘行为 ====================

    @Test
    public void test_valid_config_saved_with_backup() throws IOException {
        assumeIsolatedHome();
        seedAgentFile(ORIGINAL_YAML);

        store().writeAgentContent(VALID_YAML);

        assertEquals(VALID_YAML, Files.readString(agentFile(), StandardCharsets.UTF_8));
        assertTrue(Files.exists(agentFile().resolveSibling("agent.yml.bak")), "合法保存应保留原有备份行为");
    }

    // ==================== 工具方法 ====================

    private static String withoutProviderAndModel() {
        return VALID_YAML
                .replace("provider: deepseek-native", "provider:")
                .replace("model: deepseek-flash", "model:");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options)).load(yaml);
    }

    private static void assertContains(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(error -> error.contains(fragment)),
                () -> "期望包含 [" + fragment + "] 的错误，实际: " + errors);
    }

    private static AgentConfigFileStore store() {
        return new AgentConfigFileStore(null);
    }

    private Path agentFile() {
        return home.resolve("config").resolve("agent.yml");
    }

    private void seedAgentFile(String content) throws IOException {
        Files.writeString(agentFile(), content, StandardCharsets.UTF_8);
    }

    private void assumeIsolatedHome() {
        Assumptions.assumeTrue(isolatedHome,
                "AI_SSH_HOME 环境变量已设置，无法隔离临时目录，跳过写盘用例");
    }

    /**
     * 校验失败的三条硬约束：抛 {@link AgentConfigValidationException}、磁盘原文件不变、
     * 不产生 .bak（避免"写了一半"）。
     */
    private void assertRejectedWithoutWrite(String candidate) throws IOException {
        assumeIsolatedHome();
        seedAgentFile(ORIGINAL_YAML);

        AgentConfigValidationException error = assertThrows(AgentConfigValidationException.class,
                () -> store().writeAgentContent(candidate));
        assertFalse(error.getMessage() == null || error.getMessage().isBlank(), "错误信息不能为空");

        assertEquals(ORIGINAL_YAML, Files.readString(agentFile(), StandardCharsets.UTF_8),
                "校验失败不得改写磁盘原文件");
        assertFalse(Files.exists(agentFile().resolveSibling("agent.yml.bak")),
                "校验失败不得产生备份文件");
    }
}

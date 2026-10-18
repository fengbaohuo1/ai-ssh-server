package jainingfei.site.ai.agent.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent / 模型配置文件存储（infrastructure 层）——配置页的唯一文件出口。
 *
 * <p>两个固定文件（桌面端「配置页」写、Spring 启动时 {@code spring.config.import} 读）：
 * <pre>
 *   ${AI_SSH_HOME}/config/agent.yml   主配置：智能体/子智能体/MCP/技能/运行治理（用户可手写、带注释）
 *   ${AI_SSH_HOME}/config/llm.yml     模型覆盖层：由模型配置表单生成，只写
 *                                     ai.agent.config.tables.&lt;tableKey&gt;.module.chat-model 下的键
 * </pre>
 * 导入顺序 agent.yml 在前、llm.yml 在后 → 同名键由 llm.yml 覆盖，因此写模型配置<b>不需要重写
 * agent.yml</b>，用户的注释与手工结构不会被表单弄丢。
 *
 * <p>有效配置读取用 {@link YamlPropertySourceLoader} + {@link Binder} 现场合并两个文件
 * （而不是注入启动期的 {@code AiAgentAutoConfigProperties} bean）：配置页保存后无需重启即可读到新值。
 *
 * <p>安全约束：{@code api-key} 只写不读——对外快照只暴露 {@code apiKeyConfigured} 布尔位。
 */
@Slf4j
@Component
public class AgentConfigFileStore {

    /** 主配置模板（打包在 classpath，首次启动由 Application / 本类兜底复制） */
    private static final String AGENT_TEMPLATE = "agent/ssh-agent.yml";

    /** 主配置的备份文件名（原文件同目录） */
    private static final String AGENT_BACKUP_NAME = "agent.yml.bak";

    private static final String OVERLAY_FILE_NAME = "llm.yml";

    /** YAML 路径：ai.agent.config.tables */
    private static final List<String> TABLES_PATH = List.of("ai", "agent", "config", "tables");

    private final Environment environment;

    public AgentConfigFileStore(Environment environment) {
        this.environment = environment;
    }

    // ==================== 路径 ====================

    public Path agentConfigPath() {
        return AiSshHome.agentConfigFile();
    }

    public Path overlayPath() {
        return AiSshHome.llmOverlayFile();
    }

    /** 对外展示用的路径串（正斜杠，避免 Windows 反斜杠在 JSON 里看起来像转义） */
    public String agentConfigPathString() {
        return forwardSlashes(agentConfigPath());
    }

    public String overlayPathString() {
        return forwardSlashes(overlayPath());
    }

    // ==================== agent.yml 读写 ====================

    /** 读取主配置原始 YAML；文件不存在时先从 classpath 模板复制。 */
    public String readAgentContent() {
        try {
            ensureAgentFile();
            return Files.readString(agentConfigPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 agent.yml 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写主配置：语法/结构校验 → 备份 agent.yml.bak → 临时文件 + ATOMIC_MOVE 落盘。
     *
     * @param content 新 YAML 全文
     * @throws AgentConfigValidationException 校验失败（JSON 侧返回 code=0002）
     */
    public void writeAgentContent(String content) {
        String normalized = content == null ? "" : content;
        validateAgentYaml(normalized);
        try {
            ensureAgentFile();
            Path target = agentConfigPath();
            backupAgentFile(target);
            atomicWrite(target, normalized);
            log.info("agent.yml 已更新 | path:{} | bytes:{}", forwardSlashes(target),
                    normalized.getBytes(StandardCharsets.UTF_8).length);
        } catch (IOException e) {
            throw new IllegalStateException("写入 agent.yml 失败: " + e.getMessage(), e);
        }
    }

    /** 校验：合法 YAML 且必须存在 ai.agent.config.tables（结构缺失给可操作提示） */
    private void validateAgentYaml(String content) {
        Object root = safeLoad(content, "agent.yml");
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new AgentConfigValidationException("agent.yml 根节点必须是对象（key: value 结构）");
        }
        Object tables = dig(rootMap, TABLES_PATH);
        if (!(tables instanceof Map<?, ?> tableMap) || tableMap.isEmpty()) {
            throw new AgentConfigValidationException(
                    "缺少 ai.agent.config.tables 配置（至少需要一个智能体配置表，例如 sshAgent）");
        }
    }

    /** 文件不存在时从 classpath 模板初始化 */
    private void ensureAgentFile() throws IOException {
        Path target = agentConfigPath();
        if (Files.exists(target)) {
            return;
        }
        boolean copied = AiSshHome.copyClasspathTemplateIfMissing(AGENT_TEMPLATE, target);
        if (!copied && !Files.exists(target)) {
            throw new IOException("主配置不存在且模板复制未生效: " + forwardSlashes(target));
        }
    }

    private void backupAgentFile(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        Path backup = target.resolveSibling(AGENT_BACKUP_NAME);
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        log.info("agent.yml 已备份 | backup:{}", forwardSlashes(backup));
    }

    // ==================== 模型配置（llm.yml 覆盖层） ====================

    /** 模型配置快照（不含 api-key 明文，只给 apiKeyConfigured） */
    public record ModelConfigSnapshot(
            String provider,
            String baseUrl,
            String model,
            Long contextSize,
            String reasoningEffort,
            Double temperature,
            Double topP,
            Integer maxTokens,
            boolean apiKeyConfigured,
            String tableKey,
            String configPath,
            String overlayPath) {
    }

    /** 模型配置写入参数（api-key 为 null/空表示"沿用现有"） */
    public record ModelConfigUpdate(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            Long contextSize,
            String reasoningEffort,
            Double temperature,
            Double topP,
            Integer maxTokens) {
    }

    /** 读取有效模型配置（agent.yml + llm.yml 合并后） */
    public ModelConfigSnapshot readModelConfig() {
        AiAgentAutoConfigProperties properties = loadEffectiveProperties();
        Map<String, AiAgentConfigTableVO> tables = properties.getTables();
        String tableKey = resolveTableKey(tables);
        AiAgentConfigTableVO.Module.ChatModel chatModel = resolveChatModel(tables, tableKey);

        return new ModelConfigSnapshot(
                chatModel == null ? null : chatModel.getProvider(),
                chatModel == null ? null : chatModel.getBaseUrl(),
                chatModel == null ? null : chatModel.getModel(),
                chatModel == null ? null : chatModel.getContextSize(),
                chatModel == null ? null : chatModel.getReasoningEffort(),
                chatModel == null ? null : chatModel.getTemperature(),
                chatModel == null ? null : chatModel.getTopP(),
                chatModel == null ? null : chatModel.getMaxTokens(),
                chatModel != null && hasText(chatModel.getApiKey()),
                tableKey,
                agentConfigPathString(),
                overlayPathString());
    }

    /**
     * 写模型配置：只写 llm.yml 中 {@code ai.agent.config.tables.<tableKey>.module.chat-model} 下的键。
     * <p>主配置 agent.yml 不重写，注释与手工结构原样保留。
     */
    public void writeModelConfig(ModelConfigUpdate update) {
        if (update == null) {
            throw new AgentConfigValidationException("请求体不能为空");
        }
        String provider = trimToNull(update.provider());
        if (provider == null) {
            throw new AgentConfigValidationException("请选择模型通道（provider）");
        }
        validateProvider(provider);

        // 有效配置（用于判定"当前是否已有 key"）
        AiAgentAutoConfigProperties effective = loadEffectiveProperties();
        String tableKey = resolveTableKey(effective.getTables());
        if (tableKey == null) {
            throw new AgentConfigValidationException(
                    "配置文件中未找到 ai.agent.config.tables，请先在 agent.yml 中完善智能体配置");
        }
        AiAgentConfigTableVO.Module.ChatModel currentChatModel = resolveChatModel(effective.getTables(), tableKey);
        String currentApiKey = currentChatModel == null ? null : trimToNull(currentChatModel.getApiKey());
        String newApiKey = trimToNull(update.apiKey());
        if (newApiKey == null && currentApiKey == null) {
            throw new AgentConfigValidationException("请填写 API Key");
        }

        try {
            Map<String, Object> root = loadExistingOverlay();
            Map<String, Object> chatModelNode = navigateChatModel(root, tableKey);
            putOrRemove(chatModelNode, "provider", provider);
            putOrRemove(chatModelNode, "base-url", trimToNull(update.baseUrl()));
            putOrRemove(chatModelNode, "model", trimToNull(update.model()));
            putOrRemove(chatModelNode, "context-size", update.contextSize());
            putOrRemove(chatModelNode, "reasoning-effort", trimToNull(update.reasoningEffort()));
            putOrRemove(chatModelNode, "temperature", update.temperature());
            putOrRemove(chatModelNode, "top-p", update.topP());
            putOrRemove(chatModelNode, "max-tokens", update.maxTokens());
            // api-key 为空 = 沿用现有（覆盖层里已有就保留，来自 agent.yml/env 的不写入）
            if (newApiKey != null) {
                chatModelNode.put("api-key", newApiKey);
            }

            atomicWrite(overlayPath(), dumpYaml(root));
            // 只打印非机密字段；api-key 绝不进日志
            log.info("llm.yml 已更新 | provider:{} | model:{} | baseUrl:{} | apiKeyConfigured:{}",
                    provider, update.model(), update.baseUrl(), newApiKey != null || currentApiKey != null);
        } catch (IOException e) {
            throw new IllegalStateException("写入 llm.yml 失败: " + e.getMessage(), e);
        }
    }

    /** provider 必须是已注册通道（取值唯一出处在 domain 的 ProviderCapabilities） */
    private static void validateProvider(String provider) {
        try {
            ProviderCapabilities.forProvider(provider);
        } catch (IllegalArgumentException e) {
            throw new AgentConfigValidationException("未知模型通道: " + provider
                    + "（可选值：" + ProviderCapabilities.DEEPSEEK_NATIVE + " / "
                    + ProviderCapabilities.OPENAI_COMPATIBLE + "）");
        }
    }

    /**
     * 读取有效属性：agent.yml 为主、llm.yml 覆盖层为后。
     * <p>每次调用都重新读文件，保证配置页保存后 GET 立即看到新值。
     */
    public AiAgentAutoConfigProperties loadEffectiveProperties() {
        ensureAgentFileForRead();
        List<PropertySource<?>> sources = new ArrayList<>();
        // PropertySources 中"先出现者优先"，与 spring.config.import 的"后导入覆盖前导入"一致
        addYamlSource(sources, "llm-overlay", overlayPath());
        addYamlSource(sources, "agent-main", agentConfigPath());

        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(environment));
        AiAgentAutoConfigProperties properties = binder
                .bind("ai.agent.config", Bindable.of(AiAgentAutoConfigProperties.class))
                .orElseGet(AiAgentAutoConfigProperties::new);
        return properties;
    }

    private void ensureAgentFileForRead() {
        try {
            ensureAgentFile();
        } catch (IOException e) {
            throw new IllegalStateException("读取 agent.yml 失败: " + e.getMessage(), e);
        }
    }

    private void addYamlSource(List<PropertySource<?>> sources, String name, Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            sources.addAll(new YamlPropertySourceLoader().load(name, new FileSystemResource(path)));
        } catch (IOException e) {
            throw new IllegalStateException("解析配置文件失败: " + forwardSlashes(path)
                    + " | " + e.getMessage(), e);
        }
    }

    // ==================== 内部：YAML 工具 ====================

    private static Object safeLoad(String content, String fileName) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            Object root = yaml.load(content);
            if (root == null) {
                throw new AgentConfigValidationException(fileName + " 内容为空");
            }
            return root;
        } catch (AgentConfigValidationException e) {
            throw e;
        } catch (YAMLException e) {
            throw new AgentConfigValidationException(fileName + " YAML 语法错误: " + firstLine(e.getMessage()));
        }
    }

    /** 覆盖层 YAML → Map（不存在时返回空 Map；语法错误按校验失败处理） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadExistingOverlay() {
        Path path = overlayPath();
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new LinkedHashMap<>();
            }
            Object root = safeLoad(content, OVERLAY_FILE_NAME);
            if (!(root instanceof Map<?, ?> map)) {
                throw new AgentConfigValidationException(OVERLAY_FILE_NAME + " 根节点必须是对象");
            }
            return (Map<String, Object>) map;
        } catch (IOException e) {
            throw new IllegalStateException("读取 llm.yml 失败: " + e.getMessage(), e);
        }
    }

    /** 定位（不存在则创建）ai.agent.config.tables.&lt;tableKey&gt;.module.chat-model */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigateChatModel(Map<String, Object> root, String tableKey) {
        Map<String, Object> node = root;
        node = childMap(node, "ai");
        node = childMap(node, "agent");
        node = childMap(node, "config");
        node = childMap(node, "tables");
        node = childMap(node, tableKey);
        node = childMap(node, "module");
        return childMap(node, "chat-model");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> child = new LinkedHashMap<>();
        parent.put(key, child);
        return child;
    }

    private static void putOrRemove(Map<String, Object> node, String key, Object value) {
        if (value == null) {
            node.remove(key);
        } else {
            node.put(key, value);
        }
    }

    private static String dumpYaml(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(root);
    }

    /** 取 ai.agent.config.tables 下第一张配置表（优先含 chat-model 的表） */
    private static String resolveTableKey(Map<String, AiAgentConfigTableVO> tables) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, AiAgentConfigTableVO> entry : tables.entrySet()) {
            if (resolveChatModel(tables, entry.getKey()) != null) {
                return entry.getKey();
            }
        }
        return tables.keySet().iterator().next();
    }

    private static AiAgentConfigTableVO.Module.ChatModel resolveChatModel(
            Map<String, AiAgentConfigTableVO> tables, String tableKey) {
        if (tables == null || tableKey == null) {
            return null;
        }
        AiAgentConfigTableVO table = tables.get(tableKey);
        if (table == null || table.getModule() == null) {
            return null;
        }
        return table.getModule().getChatModel();
    }

    /** 按路径逐层取 Map 值（任一层非 Map 返回 null） */
    private static Object dig(Map<?, ?> root, List<String> path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    // ==================== 内部：文件工具 ====================

    /**
     * 临时文件 + 原子替换。ATOMIC_MOVE 在部分文件系统（如跨卷）不可用，退化为普通替换。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("文件系统不支持原子替换，退化为普通替换 | target:{}", forwardSlashes(target));
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String forwardSlashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "未知错误";
        }
        int index = message.indexOf('\n');
        return index < 0 ? message : message.substring(0, index);
    }
}

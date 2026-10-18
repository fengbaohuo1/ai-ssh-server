package jainingfei.site.ai.agent.infrastructure.config;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent.yml 保存前置校验器（纯静态、零 Spring 依赖，可在普通 JVM 单测里直接调用）。
 *
 * <p>目标：<b>把装配期才会暴露的配置错误提前到保存时</b>，校验失败直接拒绝写入
 * （磁盘保留原文件），由 {@link AgentConfigValidationException} 经 Controller 返回
 * {@code code=0002}。校验项：
 * <ul>
 *   <li>{@code ai.agent.config.tables} 存在且非空；</li>
 *   <li>每个智能体：{@code name} 非空且表内唯一、{@code instruction} 非空、
 *       {@code delegate-mode} 只能是 {@code hybrid} / {@code dispatch-only} 或留空；</li>
 *   <li>{@code sub-agents} 引用的智能体必须在同一配置表内存在（装配期只认智能体名，
 *       workflow 名不能作为 sub-agents 的引用目标）；</li>
 *   <li>{@code runner.agent-name} 必须指向表内已存在的智能体或工作流（ADK Runner 的入口）；</li>
 *   <li>{@code chat-model.provider} / {@code chat-model.model} 非空，provider 必须属于
 *       {@link ProviderCapabilities} 注册的通道集合（唯一出处，避免硬编码漂移）；</li>
 *   <li>{@code tool-mcp-list} 条目：{@code remote} / {@code stdio} / {@code local} 三选一、
 *       有 {@code name}（remote 还要 url，stdio 还要 server-parameters.command）、
 *       {@code mcp-readonly-safe} 若出现必须是布尔。</li>
 * </ul>
 *
 * <p>provider / model 允许回退到 {@code llm.yml} 覆盖层（配置页"模型配置"只写覆盖层，
 * agent.yml 里可能本来就没写 provider/model；运行时两者合并后才是有效配置，校验必须同语义）。
 */
public final class AgentConfigValidator {

    /** YAML 路径：ai.agent.config.tables */
    private static final List<String> TABLES_PATH = List.of("ai", "agent", "config", "tables");

    /** tool-mcp-list 条目的三种形态（kind） */
    private static final List<String> MCP_KINDS = List.of("remote", "stdio", "local");

    /** 错误汇总最多列多少条，避免一次刷屏 */
    private static final int MAX_REPORTED_ERRORS = 20;

    private AgentConfigValidator() {
    }

    /**
     * llm.yml 覆盖层里某张配置表的 chat-model 回退值（仅 provider / model 两项用于"非空"判定）。
     */
    public record OverlayChatModel(String provider, String model) {
    }

    /** 校验并抛出 {@link AgentConfigValidationException}（错误汇总为多行文本） */
    public static void validateOrThrow(Map<?, ?> root) {
        validateOrThrow(root, Map.of());
    }

    /** 校验并抛出 {@link AgentConfigValidationException}（带覆盖层回退值） */
    public static void validateOrThrow(Map<?, ?> root, Map<String, OverlayChatModel> overlays) {
        List<String> errors = validate(root, overlays);
        if (!errors.isEmpty()) {
            throw new AgentConfigValidationException(summarize(errors));
        }
    }

    /** 纯函数：返回错误清单（空 = 通过），便于单测逐条断言 */
    public static List<String> validate(Map<?, ?> root) {
        return validate(root, Map.of());
    }

    /** 纯函数：返回错误清单（空 = 通过），overlays 为 tableKey → 覆盖层回退值 */
    public static List<String> validate(Map<?, ?> root, Map<String, OverlayChatModel> overlays) {
        List<String> errors = new ArrayList<>();
        if (root == null) {
            errors.add("agent.yml 根节点必须是对象（key: value 结构）");
            return errors;
        }
        Object tablesObj = dig(root, TABLES_PATH);
        if (!(tablesObj instanceof Map<?, ?> tables) || tables.isEmpty()) {
            errors.add("缺少 ai.agent.config.tables 配置（至少需要一个智能体配置表，例如 sshAgent）");
            return errors;
        }
        Map<String, OverlayChatModel> overlayMap = overlays == null ? Map.of() : overlays;
        for (Map.Entry<?, ?> entry : tables.entrySet()) {
            String tableKey = String.valueOf(entry.getKey());
            validateTable(tableKey, entry.getValue(), overlayMap.get(tableKey), errors);
        }
        return errors;
    }

    // ==================== 单张配置表 ====================

    private static void validateTable(String tableKey, Object rawTable, OverlayChatModel overlay, List<String> errors) {
        String prefix = tableKey + ": ";
        if (!(rawTable instanceof Map<?, ?> table)) {
            errors.add(prefix + "配置表必须是对象（key: value 结构）");
            return;
        }
        Object moduleObj = table.get("module");
        if (!(moduleObj instanceof Map<?, ?> module)) {
            errors.add(prefix + "缺少 module 配置（至少需要 module.chat-model / module.agents / module.runner）");
            return;
        }

        validateChatModel(prefix, module.get("chat-model"), overlay, errors);

        Map<String, Map<?, ?>> agents = validateAgents(prefix, module.get("agents"), errors);
        Map<String, Map<?, ?>> workflows = validateWorkflows(prefix, module.get("agent-workflows"),
                agents.keySet(), errors);

        validateSubAgentRefs(prefix, agents, errors);
        validateWorkflowRefs(prefix, workflows, agents, errors);
        validateRunner(prefix, module.get("runner"), agents, workflows, errors);
    }

    // ==================== chat-model ====================

    private static void validateChatModel(String prefix, Object rawChatModel, OverlayChatModel overlay,
                                         List<String> errors) {
        if (!(rawChatModel instanceof Map<?, ?> chatModel)) {
            errors.add(prefix + "缺少 module.chat-model 配置（模型通道 / 模型名 / api-key）");
            return;
        }
        String provider = text(chatModel.get("provider"));
        if (provider == null && overlay != null) {
            provider = text(overlay.provider());
        }
        if (provider == null) {
            errors.add(prefix + "module.chat-model.provider 不能为空（可选值："
                    + ProviderCapabilities.DEEPSEEK_NATIVE + " / " + ProviderCapabilities.OPENAI_COMPATIBLE + "）");
        } else if (!isSupportedProvider(provider)) {
            errors.add(prefix + "未知模型通道: " + provider + "（可选值："
                    + ProviderCapabilities.DEEPSEEK_NATIVE + " / " + ProviderCapabilities.OPENAI_COMPATIBLE + "）");
        }

        String model = text(chatModel.get("model"));
        if (model == null && overlay != null) {
            model = text(overlay.model());
        }
        if (model == null) {
            errors.add(prefix + "module.chat-model.model 不能为空");
        }

        validateToolMcpList(prefix, chatModel.get("tool-mcp-list"), errors);
    }

    private static void validateToolMcpList(String prefix, Object rawList, List<String> errors) {
        if (rawList == null) {
            return;
        }
        if (!(rawList instanceof List<?> list)) {
            errors.add(prefix + "module.chat-model.tool-mcp-list 必须是列表");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            String itemPrefix = prefix + "tool-mcp-list[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> item)) {
                errors.add(itemPrefix + " 必须是对象（remote / stdio / local 三选一）");
                continue;
            }
            List<String> kinds = new ArrayList<>();
            for (String kind : MCP_KINDS) {
                if (item.get(kind) != null) {
                    kinds.add(kind);
                }
            }
            if (kinds.size() != 1) {
                errors.add(itemPrefix + " 必须且只能声明 remote / stdio / local 之一（当前: "
                        + (kinds.isEmpty() ? "无" : String.join("+", kinds)) + "）");
            } else {
                validateMcpKindParams(itemPrefix, kinds.get(0), item.get(kinds.get(0)), errors);
            }
            Object readonlySafe = item.get("mcp-readonly-safe");
            if (readonlySafe != null && !(readonlySafe instanceof Boolean)) {
                errors.add(itemPrefix + ".mcp-readonly-safe 必须是 true / false");
            }
        }
    }

    private static void validateMcpKindParams(String itemPrefix, String kind, Object rawParams, List<String> errors) {
        if (!(rawParams instanceof Map<?, ?> params)) {
            errors.add(itemPrefix + "." + kind + " 必须是对象");
            return;
        }
        if (text(params.get("name")) == null) {
            errors.add(itemPrefix + "." + kind + ".name 不能为空（MCP server 名称，用于日志与工具溯源）");
        }
        if ("remote".equals(kind)) {
            if (text(params.get("url")) == null) {
                errors.add(itemPrefix + ".remote.url 不能为空");
            }
        } else if ("stdio".equals(kind)) {
            Object serverParameters = params.get("server-parameters");
            if (!(serverParameters instanceof Map<?, ?> sp) || text(sp.get("command")) == null) {
                errors.add(itemPrefix + ".stdio.server-parameters.command 不能为空");
            }
        }
    }

    /** 取值唯一出处 {@link ProviderCapabilities}：未知通道在装配期会抛 IllegalArgumentException */
    private static boolean isSupportedProvider(String provider) {
        try {
            ProviderCapabilities.forProvider(provider);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ==================== agents / workflows ====================

    private static Map<String, Map<?, ?>> validateAgents(String prefix, Object rawAgents, List<String> errors) {
        Map<String, Map<?, ?>> byName = new LinkedHashMap<>();
        if (!(rawAgents instanceof List<?> list) || list.isEmpty()) {
            errors.add(prefix + "module.agents 至少需要一个智能体配置");
            return byName;
        }
        for (int i = 0; i < list.size(); i++) {
            String itemPrefix = prefix + "agents[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> agent)) {
                errors.add(itemPrefix + " 必须是对象");
                continue;
            }
            String name = text(agent.get("name"));
            String label;
            if (name == null) {
                errors.add(itemPrefix + ".name 不能为空（智能体名用于 sub-agents 引用与 runner.agent-name）");
                label = itemPrefix;
            } else if (byName.containsKey(name)) {
                errors.add(itemPrefix + ".name 重复: " + name + "（同一配置表内智能体名必须唯一）");
                label = "智能体 " + name;
            } else {
                byName.put(name, agent);
                label = "智能体 " + name;
            }
            if (text(agent.get("instruction")) == null) {
                errors.add(prefix + label + " 的 instruction 不能为空");
            }
            validateDelegateMode(prefix, label, text(agent.get("delegate-mode")), errors);
            validateStringList(prefix + label + " 的 sub-agents", agent.get("sub-agents"), errors);
        }
        return byName;
    }

    private static void validateDelegateMode(String prefix, String label, String delegateMode, List<String> errors) {
        if (delegateMode == null) {
            return;   // 缺省按 hybrid
        }
        String mode = delegateMode.toLowerCase();
        if (!"hybrid".equals(mode) && !"dispatch-only".equals(mode)) {
            errors.add(prefix + label + " 的 delegate-mode 非法: " + delegateMode
                    + "（可选值：hybrid / dispatch-only，留空按 hybrid）");
        }
    }

    private static Map<String, Map<?, ?>> validateWorkflows(String prefix, Object rawWorkflows,
                                                            java.util.Set<String> agentNames, List<String> errors) {
        Map<String, Map<?, ?>> byName = new LinkedHashMap<>();
        if (rawWorkflows == null) {
            return byName;
        }
        if (!(rawWorkflows instanceof List<?> list)) {
            errors.add(prefix + "module.agent-workflows 必须是列表");
            return byName;
        }
        for (int i = 0; i < list.size(); i++) {
            String itemPrefix = prefix + "agent-workflows[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> workflow)) {
                errors.add(itemPrefix + " 必须是对象");
                continue;
            }
            String name = text(workflow.get("name"));
            if (name == null) {
                errors.add(itemPrefix + ".name 不能为空（工作流名用于 runner.agent-name）");
                continue;
            }
            if (agentNames.contains(name)) {
                errors.add(itemPrefix + ".name 与智能体重名: " + name
                        + "（智能体与工作流共用 ADK 注册名空间，不能重名）");
            }
            if (byName.containsKey(name)) {
                errors.add(itemPrefix + ".name 重复: " + name + "（同一配置表内工作流名必须唯一）");
            } else {
                byName.put(name, workflow);
            }
            validateStringList(prefix + "工作流 " + name + " 的 sub-agents", workflow.get("sub-agents"), errors);
        }
        return byName;
    }

    /** 智能体的 sub-agents 引用的必须是本表内已定义的智能体（装配期只认智能体名） */
    private static void validateSubAgentRefs(String prefix, Map<String, Map<?, ?>> agents, List<String> errors) {
        for (Map.Entry<String, Map<?, ?>> entry : agents.entrySet()) {
            for (String ref : stringList(entry.getValue().get("sub-agents"))) {
                if (!agents.containsKey(ref)) {
                    errors.add(prefix + "智能体 " + entry.getKey()
                            + " 的 sub-agents 引用了不存在的智能体: " + ref);
                }
            }
        }
    }

    /** 工作流的 sub-agents 同样只能引用智能体 */
    private static void validateWorkflowRefs(String prefix, Map<String, Map<?, ?>> workflows,
                                             Map<String, Map<?, ?>> agents, List<String> errors) {
        for (Map.Entry<String, Map<?, ?>> entry : workflows.entrySet()) {
            for (String ref : stringList(entry.getValue().get("sub-agents"))) {
                if (!agents.containsKey(ref)) {
                    errors.add(prefix + "工作流 " + entry.getKey()
                            + " 的 sub-agents 引用了不存在的智能体: " + ref);
                }
            }
        }
    }

    // ==================== runner ====================

    private static void validateRunner(String prefix, Object rawRunner, Map<String, Map<?, ?>> agents,
                                       Map<String, Map<?, ?>> workflows, List<String> errors) {
        if (!(rawRunner instanceof Map<?, ?> runner)) {
            errors.add(prefix + "缺少 module.runner 配置（runner.agent-name 用于指定 Runner 入口智能体）");
            return;
        }
        String agentName = text(runner.get("agent-name"));
        if (agentName == null) {
            errors.add(prefix + "module.runner.agent-name 不能为空");
        } else if (!agents.containsKey(agentName) && !workflows.containsKey(agentName)) {
            errors.add(prefix + "module.runner.agent-name 引用了不存在的智能体或工作流: " + agentName);
        }
        validateStringList(prefix + "module.runner.plugin-name-list", runner.get("plugin-name-list"), errors);
    }

    // ==================== 工具方法 ====================

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

    private static String text(Object value) {
        if (!(value instanceof String string)) {
            return null;
        }
        String trimmed = string.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 列表逐项非空校验（null 视为不配置，直接放行） */
    private static void validateStringList(String label, Object rawList, List<String> errors) {
        if (rawList == null) {
            return;
        }
        if (!(rawList instanceof List<?> list)) {
            errors.add(label + " 必须是列表");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (text(list.get(i)) == null) {
                errors.add(label + "[" + i + "] 不能为空");
            }
        }
    }

    /** 取列表里的非空字符串（用于引用存在性校验） */
    private static List<String> stringList(Object rawList) {
        List<String> values = new ArrayList<>();
        if (!(rawList instanceof List<?> list)) {
            return values;
        }
        for (Object item : list) {
            String value = text(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static String summarize(List<String> errors) {
        StringBuilder builder = new StringBuilder("配置校验未通过，已保留磁盘上的原文件：");
        int limit = Math.min(errors.size(), MAX_REPORTED_ERRORS);
        for (int i = 0; i < limit; i++) {
            builder.append('\n').append(i + 1).append(". ").append(errors.get(i));
        }
        if (errors.size() > limit) {
            builder.append('\n').append("…… 其余 ").append(errors.size() - limit).append(" 条从略");
        }
        return builder.toString();
    }
}

package jainingfei.site.ai.agent.domain.agent.service.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import jakarta.annotation.Resource;
import jianingfei.site.wrench.design.framework.tree.StrategyHandler;
import jainingfei.site.ai.agent.domain.agent.model.entity.ArmoryCommandEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentRegisterVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.AbstractArmorySupport;
import jainingfei.site.ai.agent.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiLlmAdapter;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.ListRemoteFilesTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.ReadRemoteFileTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.WriteRemoteFileTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.BatchSubAgentDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.DynamicAgentOrchestrator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.DynamicPlanDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanParser;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlanValidator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.PlannerAgentBuilder;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能体装配节点——<b>二段式装配</b>（教程 2-10）。
 *
 * <p>第一段：按配置把每个智能体建成 {@link LlmAgent}，统一挂上"SSH 命令 + MCP/技能"工具集。
 * <p>第二段 {@link #buildAgentTools}：对<b>声明了 {@code sub-agents} 的智能体</b>，
 * 用同名智能体重建并覆盖 {@code agentGroup}，工具集由 {@code delegate-mode} 决定：
 * <ul>
 *   <li>{@code hybrid}（缺省）：保留第一段的执行工具，再<b>追加</b>派发工具集（每个子智能体一个
 *       {@link SubAgentDispatchTool} + {@link BatchSubAgentDispatchTool} + {@link DynamicPlanDispatchTool}）。
 *       简单命令父智能体自己执行，复杂/需要隔离的任务再派发——避免"凡是命令都派发"的刚性</li>
 *   <li>{@code dispatch-only}：工具换成派发工具集，<b>不再持有 executeCommand</b>：
 *       父智能体成为"只拆解、不执行"的编排者，权责边界由架构保证而不是靠提示词自觉</li>
 * </ul>
 *
 * <p>两个实现细节：
 * <ul>
 *   <li>{@code LlmAgent} 构建完成后工具不可变（ADK Builder 约束），所以只能"用相同 name 重建"再覆盖</li>
 *   <li>第二段在所有智能体都进 {@code agentGroup} 之后执行，因此与 YAML 里 agents 的书写顺序无关；
 *       子智能体名写错则在<b>装配期</b>直接抛异常（fail-fast），而不是等到运行期才发现</li>
 * </ul>
 *
 * <p>MCP/技能工具集保持对所有智能体可见；SSH 执行工具按智能体实例化（prototype），
 * 只读智能体拿到的是"命令级只读"的那一份，read-only 不只是不挂 writeRemoteFile。
 */
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    /** prototype 作用域：每个智能体持有独立的 executeCommand 实例，只读策略随实例走 */
    @Resource
    private ObjectProvider<SshExecuteAdkTool> sshExecuteAdkToolProvider;

    @Resource
    private ListRemoteFilesTool listRemoteFilesTool;

    @Resource
    private ReadRemoteFileTool readRemoteFileTool;

    @Resource
    private WriteRemoteFileTool writeRemoteFileTool;

    @Resource
    private SubAgentDispatchService subAgentDispatchService;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;

    @Resource
    private PlanParser planParser;

    @Resource
    private PlanValidator planValidator;

    /** appName → 当前生效版本装配出的工具集（热重装提交时用于回收上一版，避免 MCP session 泄漏） */
    private final Map<String, List<BaseToolset>> activeToolsets = new ConcurrentHashMap<>();

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        ProviderProfile providerProfile = dynamicContext.getProviderProfile();

        // MCP/技能工具集（ChatModelNode 装配）：不再向共享列表里塞 SSH 工具，
        // 保证父智能体重建时可以只拿到"非执行类"工具
        List<BaseToolset> mcpOnlyToolsets = new ArrayList<>(dynamicContext.getMcpToolsets());

        // 第一段：全员装配。执行型智能体拿到"命令 + 文件读写"工具集；
        // read-only=true 的诊断型智能体不挂 writeRemoteFile，能力边界由配置决定。
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            dynamicContext.getAgentGroup().put(agentConfig.getName(),
                    buildAgent(agentConfig, providerProfile,
                            leafToolsets(agentConfig, chatModelConfig, mcpOnlyToolsets)));
        }

        // 第二段：为声明了 sub-agents 的智能体换上派发工具集
        buildAgentTools(dynamicContext, agents, providerProfile, mcpOnlyToolsets, chatModelConfig);

        return router(requestParameter, dynamicContext);
    }

    /** 按配置构建 LlmAgent（V3 托管循环：适配器输出原生 FunctionCall part，Runner 内部循环推进） */
    private LlmAgent buildAgent(AiAgentConfigTableVO.Module.Agent agentConfig,
                                ProviderProfile providerProfile,
                                List<?> tools) {
        return LlmAgent.builder()
                .name(agentConfig.getName())
                .instruction(agentConfig.getInstruction())
                .description(agentConfig.getDescription())
                .model(new SpringAiLlmAdapter(providerProfile))
                .outputKey(agentConfig.getOutputKey())
                .tools(tools)
                .build();
    }

    /**
     * 执行型智能体的工具集：命令执行 + 目录浏览 + 文件读取，非只读时再加文件写入。
     *
     * <p>MCP / 技能工具对执行型智能体保持可见；<b>只读智能体默认不挂载 MCP 工具集</b>
     * （MCP 工具语义不可判定，可能含写操作），只有配置了 {@code mcp-readonly-safe: true}
     * 的 MCP 条目才放行——见 {@link #mcpToolsetsFor}。executeCommand 每个智能体一个实例
     * （prototype），只读智能体的实例会在命令级拦截写操作（见 {@link SshExecuteAdkTool#setReadOnly}）。
     */
    private List<Object> leafToolsets(AiAgentConfigTableVO.Module.Agent agentConfig,
                                     AiAgentConfigTableVO.Module.ChatModel chatModelConfig,
                                     List<BaseToolset> mcpOnlyToolsets) {
        List<Object> tools = new ArrayList<>();
        tools.add(new StaticToolset(leafToolList(agentConfig, "执行型")));
        tools.addAll(mcpToolsetsFor(agentConfig, chatModelConfig, mcpOnlyToolsets));
        return tools;
    }

    /** 执行类叶子工具（每个智能体独立实例，read-only 决定写边界） */
    private List<BaseTool> leafToolList(AiAgentConfigTableVO.Module.Agent agentConfig, String scene) {
        boolean readOnly = Boolean.TRUE.equals(agentConfig.getReadOnly());
        List<BaseTool> sshTools = new ArrayList<>();
        sshTools.add(newSshExecuteAdkTool(readOnly));
        sshTools.add(listRemoteFilesTool);
        sshTools.add(readRemoteFileTool);
        if (!readOnly) {
            sshTools.add(writeRemoteFileTool);
        }
        log.info("{}工具集 | agent:{} | readOnly:{} | tools:[{}]", scene,
                agentConfig.getName(), readOnly,
                sshTools.stream().map(BaseTool::name).collect(Collectors.joining(", ")));
        return sshTools;
    }

    /** 取一个独立的 executeCommand 工具实例并绑定该智能体的只读策略 */
    private SshExecuteAdkTool newSshExecuteAdkTool(boolean readOnly) {
        SshExecuteAdkTool tool = sshExecuteAdkToolProvider.getObject();
        tool.setReadOnly(readOnly);
        return tool;
    }

    /**
     * 第二段装配：为父智能体装派发工具，工具集按 {@code delegate-mode} 决定。
     *
     * <p>hybrid（缺省）= 执行工具 + 派发工具；dispatch-only = 仅派发工具。
     * 非法取值在装配期直接抛异常（fail-fast，而不是运行期才发现）。
     *
     * @param mcpOnlyToolsets 不含 SSH 执行工具的工具集（父智能体保留 MCP/技能能力）
     */
    private void buildAgentTools(DefaultArmoryFactory.DynamicContext dynamicContext,
                                 List<AiAgentConfigTableVO.Module.Agent> agents,
                                 ProviderProfile providerProfile,
                                 List<BaseToolset> mcpOnlyToolsets,
                                 AiAgentConfigTableVO.Module.ChatModel chatModelConfig) {
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            List<String> subAgentNames = agentConfig.getSubAgents();
            if (subAgentNames == null || subAgentNames.isEmpty()) {
                continue;   // 未声明子智能体：保持第一段的装配结果（行为与旧版一致）
            }

            String delegateMode = resolveDelegateMode(agentConfig);
            boolean hybrid = AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_HYBRID.equals(delegateMode);

            List<BaseTool> dispatchTools = new ArrayList<>();
            for (String subAgentName : subAgentNames) {
                BaseAgent subAgent = dynamicContext.getAgentGroup().get(subAgentName);
                if (subAgent == null) {
                    throw new IllegalArgumentException("sub agent not found: " + subAgentName
                            + "（父智能体 " + agentConfig.getName() + " 的 sub-agents 声明了未装配的智能体名）");
                }
                dispatchTools.add(new SubAgentDispatchTool(subAgent, subAgentDispatchService));
            }
            dispatchTools.add(new BatchSubAgentDispatchTool(
                    subAgentNames, dynamicAgentOrchestrator, planParser, planValidator));
            dispatchTools.add(new DynamicPlanDispatchTool(
                    plannerAgentBuilder, dynamicAgentOrchestrator, planParser, planValidator,
                    chatModelConfig, subAgentNames));

            // hybrid：父智能体保留直接执行能力，再追加派发工具；dispatch-only：只给派发工具
            List<BaseTool> parentTools = new ArrayList<>();
            if (hybrid) {
                parentTools.addAll(leafToolList(agentConfig, "混合编排型"));
            }
            parentTools.addAll(dispatchTools);

            List<Object> tools = new ArrayList<>();
            tools.add(new StaticToolset(parentTools));
            tools.addAll(mcpToolsetsFor(agentConfig, chatModelConfig, mcpOnlyToolsets));

            dynamicContext.getAgentGroup().put(agentConfig.getName(),
                    buildAgent(agentConfig, providerProfile, tools));

            log.info("子Agent装配完成 | parent:{} | mode:{} | subAgents:[{}] | tools:[{}]",
                    agentConfig.getName(), delegateMode, String.join(",", subAgentNames),
                    parentTools.stream().map(BaseTool::name).collect(Collectors.joining(", ")));
        }
    }

    /** 解析派发模式：缺省 hybrid；非法值装配期 fail-fast */
    private static String resolveDelegateMode(AiAgentConfigTableVO.Module.Agent agentConfig) {
        String raw = agentConfig.getDelegateMode();
        if (raw == null || raw.isBlank()) {
            return AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_HYBRID;
        }
        String mode = raw.trim().toLowerCase();
        if (!AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_HYBRID.equals(mode)
                && !AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_DISPATCH_ONLY.equals(mode)) {
            throw new IllegalArgumentException("未知 delegate-mode: " + raw
                    + "（智能体 " + agentConfig.getName() + "；可选 hybrid / dispatch-only）");
        }
        return mode;
    }

    /**
     * 该智能体实际可挂载的 MCP/技能工具集。
     *
     * <p>规则（v3.2 只读边界扩展）：非只读智能体全量挂载；只读智能体<b>默认不挂载任何 MCP 条目</b>，
     * 只有该 MCP 条目声明 {@code mcp-readonly-safe: true}（确认 server 只提供只读/查询类工具）才放行。
     * 技能（skills）工具集不是 MCP，始终保留。
     *
     * <p>边界计算：ChatModelNode 先按 tool-mcp-list 顺序 1:1 追加 MCP toolset、再追加 skills 工具集，
     * 因此用 {@code toolMcpList.size()} 切分，而不是猜列表结构；数量不一致时按全量挂载并告警。
     */
    private List<BaseToolset> mcpToolsetsFor(AiAgentConfigTableVO.Module.Agent agentConfig,
                                             AiAgentConfigTableVO.Module.ChatModel chatModelConfig,
                                             List<BaseToolset> allToolsets) {
        if (allToolsets == null) {
            return List.of();
        }
        if (chatModelConfig == null || allToolsets.isEmpty()) {
            return allToolsets;
        }
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> mcpEntries = chatModelConfig.getToolMcpList();
        int mcpCount = mcpEntries == null ? 0 : mcpEntries.size();
        if (mcpCount > allToolsets.size()) {
            log.warn("MCP 工具集数量与配置不一致，按全量挂载 | agent:{} | mcpCount:{} | toolsets:{}",
                    agentConfig.getName(), mcpCount, allToolsets.size());
            mcpCount = allToolsets.size();
        }
        boolean readOnly = Boolean.TRUE.equals(agentConfig.getReadOnly());
        List<BaseToolset> toolsets = new ArrayList<>();
        for (int i = 0; i < mcpCount; i++) {
            BaseToolset toolset = allToolsets.get(i);
            if (toolset == null) {
                continue;
            }
            AiAgentConfigTableVO.Module.ChatModel.ToolMcp entry =
                    mcpEntries != null && i < mcpEntries.size() ? mcpEntries.get(i) : null;
            String serverName = mcpServerName(entry, i);
            boolean safe = entry != null && Boolean.TRUE.equals(entry.getMcpReadonlySafe());
            if (!readOnly || safe) {
                toolsets.add(toolset);
                if (readOnly) {
                    log.info("MCP 工具集放行 | agent:{} | server:{} | mcp-readonly-safe:true（只读智能体显式放行）",
                            agentConfig.getName(), serverName);
                }
            } else {
                log.info("MCP 工具集未挂载 | agent:{} | server:{} | 原因:read-only 智能体默认不挂载 MCP"
                                + "（工具语义不可判定，可能含写操作），确认只读可加 mcp-readonly-safe:true",
                        agentConfig.getName(), serverName);
            }
        }
        // skills 工具集：不是 MCP，语义只读（注入指令 + 目录读取），只读智能体也保留
        for (int i = mcpCount; i < allToolsets.size(); i++) {
            BaseToolset toolset = allToolsets.get(i);
            if (toolset != null) {
                toolsets.add(toolset);
            }
        }
        return toolsets;
    }

    /** MCP server 名（日志用）：优先配置里的 name，取不到用下标兜底 */
    private static String mcpServerName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp entry, int index) {
        if (entry != null) {
            if (entry.getRemote() != null && entry.getRemote().getName() != null) {
                return entry.getRemote().getName();
            }
            if (entry.getStdio() != null && entry.getStdio().getName() != null) {
                return entry.getStdio().getName();
            }
            if (entry.getLocal() != null && entry.getLocal().getName() != null) {
                return entry.getLocal().getName();
            }
        }
        return "mcp[" + index + "]";
    }

    /**
     * 热重装提交点：装配<b>成功之后</b>由
     * {@link jainingfei.site.ai.agent.domain.agent.service.armory.ArmoryService} 调用——
     * 登记本版工具集，并关闭上一版装配产出的 MCP/技能工具集
     * （否则 MCP session / stdio 子进程会随每次保存泄漏）。
     *
     * <p>安全约束：
     * <ul>
     *   <li>只关本类登记过的旧实例，不碰 {@code CustomAdkSessionService}、不碰 Runner / Agent；</li>
     *   <li>旧列表里仍出现在本版列表中的实例（复用）跳过不关；</li>
     *   <li>单个 close 失败只 WARN 并继续，绝不影响装配结果；</li>
     *   <li>装配失败路径不会调用本方法 —— 旧工具集保持可用，旧装配继续服务。</li>
     * </ul>
     */
    public void recyclePreviousMcpToolsets(String appName, List<BaseToolset> currentToolsets) {
        String key = appName == null || appName.isBlank() ? "default" : appName;
        List<BaseToolset> current = currentToolsets == null ? List.of() : new ArrayList<>(currentToolsets);
        List<BaseToolset> previous = activeToolsets.put(key, current);
        if (previous == null || previous.isEmpty()) {
            log.info("热重装工具集登记 | appName:{} | 本版:{} | 旧版:0（首次装配，无需回收）", key, current.size());
            return;
        }
        int closed = 0;
        for (BaseToolset toolset : previous) {
            if (toolset == null || current.contains(toolset)) {
                continue;   // 同一实例本版仍在使用，不能关
            }
            try {
                toolset.close();
                closed++;
            } catch (Exception e) {
                log.warn("热重装回收旧 MCP 工具集失败（忽略，继续回收）| appName:{} | toolset:{} | {}",
                        key, toolset, e.getMessage());
            }
        }
        log.info("热重装回收旧 MCP 工具集 | appName:{} | 旧版:{} | 已关闭:{} | 本版:{}",
                key, previous.size(), closed, current.size());
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }
}

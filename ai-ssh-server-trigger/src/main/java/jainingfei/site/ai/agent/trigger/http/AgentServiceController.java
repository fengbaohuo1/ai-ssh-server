package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.IAgentService;
import jainingfei.site.ai.agent.api.dto.*;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.cases.react.IAIAgentReActServiceCase;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ChatModelFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * V2.1: 步骤事件改为从 ADK Event Stream 提取，移除 AgentStepBuffer + 后台轮询。
 * <p>
 * ADK 管理工具循环后，function_call / function_response 自然出现在 event stream 中。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

    @Resource
    private SessionGovernanceProperties sessionGovernanceProperties;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");
            List<AiAgentConfigTableVO> tables = chatService.queryAiAgentConfigList();
            List<AiAgentConfigResponseDTO> responseDTOS = tables.stream()
                    .map(this::toConfigResponse)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(responseDTOS).build();
        } catch (AppException e) {
            return Response.<List<AiAgentConfigResponseDTO>>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("查询失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 通道能力声明（唯一出处：{@link ProviderCapabilities}）。
     * <p>配置写了未知通道时返回 {@code null} 而不是抛错——配置回传要能把"写错了"展示出来，
     * 真正的 fail-fast 在装配期（{@link ChatModelFactory#createMain}）。
     */
    private static ProviderCapabilities resolveCapabilities(String providerId) {
        try {
            return ProviderCapabilities.forProvider(providerId);
        } catch (IllegalArgumentException e) {
            log.warn("配置回传遇到未知模型通道: {}", e.getMessage());
            return null;
        }
    }

    /** 通道能力声明 → 响应 DTO */
    private static AiAgentConfigResponseDTO.CapabilitiesInfo toCapabilitiesInfo(ProviderCapabilities capabilities) {
        if (capabilities == null) {
            return null;
        }
        AiAgentConfigResponseDTO.CapabilitiesInfo info = new AiAgentConfigResponseDTO.CapabilitiesInfo();
        info.setReasoningDraftStreaming(capabilities.isReasoningDraftStreaming());
        info.setReasoningDraftReplay(capabilities.isReasoningDraftReplay());
        info.setCacheMetricsGeneric(capabilities.isCacheMetricsGeneric());
        info.setThinkingToggle(capabilities.isThinkingToggle());
        info.setReasoningEffortValues(capabilities.getReasoningEffortValues());
        info.setNote(capabilities.getNote());
        return info;
    }

    /** 配置表 VO → 响应 DTO（剔除 apiKey 等机密；模块/能力缺省为空列表） */
    private AiAgentConfigResponseDTO toConfigResponse(AiAgentConfigTableVO vo) {
        if (vo == null || vo.getAgent() == null) {
            return null;
        }
        AiAgentConfigResponseDTO dto = new AiAgentConfigResponseDTO();
        AiAgentConfigTableVO.Agent agent = vo.getAgent();
        dto.setAgentId(agent.getAgentId());
        dto.setAgentName(agent.getAgentName());
        dto.setAgentDesc(agent.getAgentDesc());
        dto.setAppName(vo.getAppName());
        dto.setRuntime(runtimeInfo());
        dto.setConfigWarnings(new ArrayList<>());
        dto.setBuiltinTools(new ArrayList<>());

        AiAgentConfigTableVO.Module module = vo.getModule();
        if (module != null) {
            AiAgentConfigTableVO.Module.ChatModel chatModel = module.getChatModel();
            if (chatModel != null) {
                AiAgentConfigResponseDTO.ChatModelInfo modelInfo = new AiAgentConfigResponseDTO.ChatModelInfo();
                ProviderCapabilities capabilities = resolveCapabilities(chatModel.getProvider());
                modelInfo.setProvider(ChatModelFactory.normalizeProviderId(chatModel.getProvider()));
                modelInfo.setProviderName(capabilities == null ? null : capabilities.getDisplayName());
                modelInfo.setCapabilities(toCapabilitiesInfo(capabilities));
                modelInfo.setModelName(chatModel.getModel());
                modelInfo.setBaseUrl(chatModel.getBaseUrl());
                modelInfo.setContextSize(chatModel.getContextSize());
                modelInfo.setReasoningEffort(chatModel.getReasoningEffort());
                modelInfo.setTemperature(chatModel.getTemperature());
                modelInfo.setMaxTokens(chatModel.getMaxTokens());
                modelInfo.setTopP(chatModel.getTopP());
                dto.setChatModel(modelInfo);
                collectWarnings(chatModel, capabilities, dto.getConfigWarnings());
            } else {
                dto.getConfigWarnings().add("未配置 chat-model，智能体无法装配模型");
            }

            // 子智能体名
            if (ObjectUtils.isNotEmpty(module.getAgents())) {
                dto.setSubAgents(module.getAgents().stream()
                        .map(AiAgentConfigTableVO.Module.Agent::getName)
                        .collect(Collectors.toList()));
                // 角色与派发关系：谁声明了 sub-agents（编排者）、可以派给谁
                dto.setAgentRoles(module.getAgents().stream()
                        .map(a -> {
                            AiAgentConfigResponseDTO.AgentRoleInfo role = new AiAgentConfigResponseDTO.AgentRoleInfo();
                            role.setName(a.getName());
                            role.setDescription(a.getDescription());
                            role.setOutputKey(a.getOutputKey());
                            role.setDelegates(a.getSubAgents() == null ? new ArrayList<>() : new ArrayList<>(a.getSubAgents()));
                            boolean orchestrator = ObjectUtils.isNotEmpty(a.getSubAgents());
                            boolean readOnly = Boolean.TRUE.equals(a.getReadOnly());
                            String delegateMode = orchestrator ? resolveDelegateMode(a.getDelegateMode()) : null;
                            if (orchestrator && delegateMode == null) {
                                dto.getConfigWarnings().add("智能体 " + a.getName()
                                        + " 的 delegate-mode 取值非法，装配期会失败（可选 hybrid / dispatch-only）");
                            }
                            role.setOrchestrator(orchestrator);
                            role.setDelegateMode(delegateMode);
                            role.setReadOnly(readOnly);
                            role.setTools(toolsFor(orchestrator, delegateMode, readOnly));
                            role.setInstructionPreview(truncate(a.getInstruction(), 600));
                            return role;
                        })
                        .collect(Collectors.toList()));
                // 内置工具并集（去重保序）
                for (AiAgentConfigResponseDTO.AgentRoleInfo role : dto.getAgentRoles()) {
                    for (String tool : role.getTools()) {
                        if (!dto.getBuiltinTools().contains(tool)) {
                            dto.getBuiltinTools().add(tool);
                        }
                    }
                }
            } else {
                dto.setSubAgents(new ArrayList<>());
                dto.setAgentRoles(new ArrayList<>());
                dto.getConfigWarnings().add("配置未声明任何智能体（module.agents 为空）");
            }

            // 工作流
            if (ObjectUtils.isNotEmpty(module.getAgentWorkflows())) {
                dto.setAgentWorkflows(module.getAgentWorkflows().stream()
                        .map(wf -> {
                            AiAgentConfigResponseDTO.AgentWorkflowInfo info = new AiAgentConfigResponseDTO.AgentWorkflowInfo();
                            info.setName(wf.getName());
                            info.setType(wf.getType());
                            info.setDescription(wf.getDescription());
                            return info;
                        })
                        .collect(Collectors.toList()));
            } else {
                dto.setAgentWorkflows(new ArrayList<>());
            }

            // 运行插件
            if (module.getRunner() != null && ObjectUtils.isNotEmpty(module.getRunner().getPluginNameList())) {
                dto.setPlugins(new ArrayList<>(module.getRunner().getPluginNameList()));
            } else {
                dto.setPlugins(new ArrayList<>());
            }

            // MCP 工具 + 技能（挂在 chat-model 下）
            if (chatModel != null) {
                dto.setMcpTools(mapMcpTools(chatModel));
                dto.setSkills(mapSkills(chatModel));
            } else {
                dto.setMcpTools(new ArrayList<>());
                dto.setSkills(new ArrayList<>());
            }
        } else {
            dto.setSubAgents(new ArrayList<>());
            dto.setAgentRoles(new ArrayList<>());
            dto.setAgentWorkflows(new ArrayList<>());
            dto.setMcpTools(new ArrayList<>());
            dto.setSkills(new ArrayList<>());
            dto.setPlugins(new ArrayList<>());
        }
        return dto;
    }

    /** 运行期治理参数快照 */
    private AiAgentConfigResponseDTO.RuntimeInfo runtimeInfo() {
        AiAgentConfigResponseDTO.RuntimeInfo runtime = new AiAgentConfigResponseDTO.RuntimeInfo();
        runtime.setMaxLlmCalls(sessionGovernanceProperties.getLoop().getMaxLlmCalls());
        runtime.setCompactEnabled(sessionGovernanceProperties.getCompact().isEnabled());
        runtime.setCompactTokenThreshold(sessionGovernanceProperties.getCompact().getTokenThreshold());
        runtime.setCompactEventRetentionSize(sessionGovernanceProperties.getCompact().getEventRetentionSize());
        runtime.setContextTokenBudget(sessionGovernanceProperties.getContextTokenBudget());
        runtime.setColdStartReplayBudgetChars(sessionGovernanceProperties.getColdStart().getReplayBudgetChars());
        return runtime;
    }

    /** 解析派发模式：空 = hybrid；非法值返回 null（由调用方收集告警） */
    private static String resolveDelegateMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_HYBRID;
        }
        String mode = raw.trim().toLowerCase();
        if (AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_HYBRID.equals(mode)
                || AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_DISPATCH_ONLY.equals(mode)) {
            return mode;
        }
        return null;
    }

    /**
     * 按角色推导内置工具。
     *
     * <p>叶子执行者：executeCommand + 文件读取（非只读再加写入）；
     * 编排者按 delegate-mode 区分：hybrid = 执行工具 + 派发工具，dispatch-only = 只派发。
     */
    private static List<String> toolsFor(boolean orchestrator, String delegateMode, boolean readOnly) {
        boolean dispatchOnly = AiAgentConfigTableVO.Module.Agent.DELEGATE_MODE_DISPATCH_ONLY.equals(delegateMode);
        boolean canExecuteDirectly = !orchestrator || !dispatchOnly;

        List<String> tools = new ArrayList<>();
        if (canExecuteDirectly) {
            tools.add("executeCommand");
            tools.add("listRemoteFiles");
            tools.add("readRemoteFile");
            if (!readOnly) {
                tools.add("writeRemoteFile");
            }
        }
        if (orchestrator) {
            tools.add("dispatchSubAgent");
            tools.add("dispatchSubAgents");
            tools.add("planAndDispatchSubAgents");
        }
        return tools;
    }

    /** 配置软问题收集（不让界面靠猜"为什么模型没反应"） */
    private static void collectWarnings(AiAgentConfigTableVO.Module.ChatModel chatModel,
                                        ProviderCapabilities capabilities,
                                        List<String> warnings) {
        if (capabilities == null) {
            warnings.add("未知模型通道: " + chatModel.getProvider()
                    + "（装配期会失败，可选 deepseek-native / openai-compatible）");
        }
        if (ObjectUtils.isEmpty(chatModel.getModel())) {
            warnings.add("未配置模型名（chat-model.model）");
        }
        if (ObjectUtils.isEmpty(chatModel.getApiKey())) {
            warnings.add("未配置 api-key，装配期将无法创建模型");
        }
        if (chatModel.getContextSize() == null || chatModel.getContextSize() <= 0) {
            warnings.add("未配置上下文窗口（chat-model.context-size），界面无法展示上下文预算");
        }
        if (chatModel.getTemperature() != null
                && (chatModel.getTemperature() < 0 || chatModel.getTemperature() > 2)) {
            warnings.add("temperature=" + chatModel.getTemperature() + " 超出常规范围 [0,2]");
        }
        if (chatModel.getMaxTokens() != null && chatModel.getMaxTokens() <= 0) {
            warnings.add("max-tokens 必须为正整数");
        }
        if (chatModel.getTopP() != null && (chatModel.getTopP() <= 0 || chatModel.getTopP() > 1)) {
            warnings.add("top-p=" + chatModel.getTopP() + " 超出常规范围 (0,1]");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** MCP 工具映射：remote/stdio/local 三种连接形态归一为 {name, kind, target} */
    private static List<AiAgentConfigResponseDTO.McpToolInfo> mapMcpTools(AiAgentConfigTableVO.Module.ChatModel chatModel) {
        List<AiAgentConfigResponseDTO.McpToolInfo> result = new ArrayList<>();
        if (ObjectUtils.isEmpty(chatModel.getToolMcpList())) {
            return result;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp mcp : chatModel.getToolMcpList()) {
            AiAgentConfigResponseDTO.McpToolInfo info = new AiAgentConfigResponseDTO.McpToolInfo();
            if (mcp.getRemote() != null) {
                info.setName(mcp.getRemote().getName());
                info.setKind("remote");
                info.setTarget(mcp.getRemote().getUrl());
            } else if (mcp.getStdio() != null) {
                info.setName(mcp.getStdio().getName());
                info.setKind("stdio");
                AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters params =
                        mcp.getStdio().getServerParameters();
                info.setTarget(params != null ? params.getCommand() : null);
            } else if (mcp.getLocal() != null) {
                info.setName(mcp.getLocal().getName());
                info.setKind("local");
            }
            result.add(info);
        }
        return result;
    }

    /** 技能映射：{type, path} 原样透出 */
    private static List<AiAgentConfigResponseDTO.SkillInfo> mapSkills(AiAgentConfigTableVO.Module.ChatModel chatModel) {
        List<AiAgentConfigResponseDTO.SkillInfo> result = new ArrayList<>();
        if (ObjectUtils.isEmpty(chatModel.getToolSkillsList())) {
            return result;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills skill : chatModel.getToolSkillsList()) {
            AiAgentConfigResponseDTO.SkillInfo info = new AiAgentConfigResponseDTO.SkillInfo();
            info.setType(skill.getType());
            info.setPath(skill.getPath());
            result.add(info);
        }
        return result;
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO dto) {
        try {
            String sid = chatService.createSession(dto.getAgentId(), dto.getUserId());
            CreateSessionResponseDTO resp = new CreateSessionResponseDTO();
            resp.setSessionId(sid);
            return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(resp).build();
        } catch (Exception e) {
            log.error("创建会话失败", e);
            return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO dto) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", dto.getAgentId(), dto.getUserId());
            String sid = dto.getSessionId();
            if (sid == null || sid.isEmpty()) sid = chatService.createSession(dto.getAgentId(), dto.getUserId());

            List<String> outputs = chatService.handleMessage(dto.getAgentId(), dto.getUserId(), sid, dto.getMessage());
            StringBuilder text = new StringBuilder();
            for (String o : outputs) {
                if (!o.startsWith("Function Call:") && !o.startsWith("Function Response:") && !o.isEmpty()) {
                    text.append(o);
                }
            }
            ChatResponseDTO resp = new ChatResponseDTO();
            resp.setContent(text.toString());
            return Response.<ChatResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(resp).build();
        } catch (Exception e) {
            log.error("对话失败", e);
            return Response.<ChatResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO dto) {
        try {
            log.info("ReAct 流式对话 agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    dto.getAgentId(), dto.getUserId(), dto.getSessionId(), dto.getTerminalSessionId());

            // 如果未指定 sessionId，先创建
            if (dto.getSessionId() == null || dto.getSessionId().isEmpty()) {
                dto.setSessionId(chatService.createSession(dto.getAgentId(), dto.getUserId()));
            }

            // 委托给 Case 层执行 ReAct 编排
            return reactServiceCase.chatStream(dto);

        } catch (Exception e) {
            log.error("流式对话初始化失败", e);
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(e);
            return emitter;
        }
    }

    @RequestMapping(value = "query_session_list", method = RequestMethod.GET)
    @Override
    public Response<List<ChatSessionResponseDTO>> querySessionList(String agentId, String userId, Integer limit) {
        try {
            log.info("查询会话列表 agentId:{} userId:{}", agentId, userId);
            List<ChatSessionEntity> sessions = chatService.querySessionList(agentId, userId, limit != null ? limit : 20);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            List<ChatSessionResponseDTO> dtoList = sessions.stream().map(s -> {
                ChatSessionResponseDTO dto = new ChatSessionResponseDTO();
                dto.setSessionId(s.getId());
                dto.setAgentId(s.getAgentId());
                dto.setUserId(s.getUserId());
                dto.setTitle(s.getTitle());
                dto.setMessageCount(s.getMessageCount());
                dto.setUpdatedAt(s.getUpdatedAt() != null ? s.getUpdatedAt().format(fmt) : null);
                return dto;
            }).collect(Collectors.toList());
            return Response.<List<ChatSessionResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(dtoList).build();
        } catch (Exception e) {
            log.error("查询会话列表失败", e);
            return Response.<List<ChatSessionResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @RequestMapping(value = "query_message_list", method = RequestMethod.GET)
    @Override
    public Response<List<ChatMessageResponseDTO>> queryMessageList(String sessionId, Integer limit) {
        try {
            log.info("查询会话消息列表 sessionId:{}", sessionId);
            List<ChatMessageEntity> messages = chatService.queryMessageList(sessionId, limit != null ? limit : 100);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            List<ChatMessageResponseDTO> dtoList = messages.stream().map(m -> {
                ChatMessageResponseDTO dto = new ChatMessageResponseDTO();
                dto.setRole(m.getRole());
                dto.setContent(m.getContent());
                dto.setToolName(m.getToolName());
                dto.setToolCallId(m.getToolCallId());
                dto.setCreatedAt(m.getCreatedAt() != null ? m.getCreatedAt().format(fmt) : null);
                return dto;
            }).collect(Collectors.toList());
            return Response.<List<ChatMessageResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(dtoList).build();
        } catch (Exception e) {
            log.error("查询会话消息列表失败", e);
            return Response.<List<ChatMessageResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

}

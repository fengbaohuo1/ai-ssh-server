package jainingfei.site.ai.agent.test.domain.agent.llm;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftProtocol;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiPromptAssembler;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求组装器（SpringAiPromptAssembler）单元测试——ADK LlmRequest → Spring AI Prompt，无网络无 Spring 容器。
 *
 * <p>覆盖：
 * <ol>
 *   <li>角色映射：system 文本汇成一条前置 {@code SystemMessage}；user 文本 → {@code UserMessage}；
 *       FunctionResponse part → {@code ToolResponseMessage}（按 id/name 与原 assistant tool_calls 配对）；
 *       model/assistant → 经草稿协议重建历史</li>
 *   <li>工具声明：per-call options 必须保留 {@code ChatModel.getOptions()} 的具体子类
 *       （provider 会强转，塞通用 options 就是 ClassCastException），并把本轮工具回调挂上去</li>
 * </ol>
 */
public class SpringAiPromptAssemblerTest {

    private final ReasoningDraftProtocol protocol = new ReasoningDraftProtocol();

    /** 只回答"默认 options 是什么"的假模型——绝不发起网络调用 */
    private static class FakeChatModel implements ChatModel {
        private final ChatOptions options;

        FakeChatModel(ChatOptions options) {
            this.options = options;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("单元测试不发起真实调用");
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    /** 带声明的假 ADK 工具——ToolConverter 只转换 declaration 存在的工具 */
    private static class FakeAdkTool extends BaseTool {
        FakeAdkTool(String name) {
            super(name, "假工具 " + name);
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                            .type(Type.Known.OBJECT)
                            .properties(Map.of("command", Schema.builder().type(Type.Known.STRING).build()))
                            .required(List.of("command"))
                            .build())
                    .build());
        }
    }

    private SpringAiPromptAssembler assembler(ChatOptions modelOptions) {
        return new SpringAiPromptAssembler(new FakeChatModel(modelOptions), protocol, null);
    }

    private static Content userText(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content functionResponseContent(String id, String name, Map<String, Object> response) {
        return Content.builder().role("user")
                .parts(List.of(Part.builder().functionResponse(FunctionResponse.builder()
                        .id(id).name(name).response(response).build()).build()))
                .build();
    }

    // ===== 角色映射 =====

    @Test
    @DisplayName("system 指令与 system 角色内容汇成一条前置 SystemMessage，排在所有消息之前")
    public void test_system_messages_collected_first() {
        LlmRequest request = LlmRequest.builder()
                .model("test-model")
                .appendInstructions(List.of("你是 SSH 运维助手"))
                .contents(List.of(
                        Content.builder().role("system").parts(List.of(Part.fromText("补充系统约束"))).build(),
                        userText("查看磁盘")))
                .build();

        Prompt prompt = assembler(null).toPrompt(request);
        List<Message> messages = prompt.getInstructions();

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0), "system 消息必须置顶");
        assertEquals("你是 SSH 运维助手\n\n补充系统约束", messages.get(0).getText(),
                "多条 system 文本用空行拼接成一条");
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("查看磁盘", messages.get(1).getText());
    }

    @Test
    @DisplayName("系统指令来自 system 角色 part 的多段文本同样只汇成一条 SystemMessage")
    public void test_multiple_system_contents_merged() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(
                        Content.builder().role("system").parts(List.of(Part.fromText("S1"))).build(),
                        Content.builder().role("system").parts(List.of(Part.fromText("S2"))).build(),
                        userText("U")))
                .build();

        Prompt prompt = assembler(null).toPrompt(request);
        assertEquals(1, prompt.getSystemMessages().size(), "SystemMessage 只允许一条");
        assertEquals("S1\n\nS2", prompt.getSystemMessages().get(0).getText());
    }

    @Test
    @DisplayName("system 角色的空文本内容被忽略，不产生空的 SystemMessage")
    public void test_blank_system_content_ignored() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(
                        Content.builder().role("system").parts(List.of()).build(),
                        userText("U")))
                .build();

        Prompt prompt = assembler(null).toPrompt(request);
        assertTrue(prompt.getSystemMessages().isEmpty());
        assertEquals(1, prompt.getInstructions().size());
    }

    @Test
    @DisplayName("user 内容里的 FunctionResponse part 转成 ToolResponseMessage，按 id/name 配对")
    public void test_function_response_to_tool_message() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(functionResponseContent("call-1", "executeCommand",
                        Map.of("success", true, "result", "ok"))))
                .build();

        Prompt prompt = assembler(null).toPrompt(request);
        List<Message> messages = prompt.getInstructions();

        assertEquals(1, messages.size(), "纯工具结果内容不额外产生空 UserMessage");
        ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(0));
        assertEquals(1, toolMessage.getResponses().size());
        assertEquals("call-1", toolMessage.getResponses().get(0).id());
        assertEquals("executeCommand", toolMessage.getResponses().get(0).name());
        assertTrue(toolMessage.getResponses().get(0).responseData().contains("\"result\":\"ok\""),
                "响应体必须是 JSON，实际 " + toolMessage.getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("user 内容同时含文本与 FunctionResponse 时产出 UserMessage + ToolResponseMessage 两条")
    public void test_user_text_and_function_response_together() {
        Content mixed = Content.builder().role("user")
                .parts(List.of(
                        Part.fromText("接着看"),
                        Part.builder().functionResponse(FunctionResponse.builder()
                                .id("call-2").name("executeCommand")
                                .response(Map.of("result", "done")).build()).build()))
                .build();

        Prompt prompt = assembler(null).toPrompt(
                LlmRequest.builder().model("m").contents(List.of(mixed)).build());
        List<Message> messages = prompt.getInstructions();

        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("接着看", messages.get(0).getText());
        assertInstanceOf(ToolResponseMessage.class, messages.get(1));
    }

    @Test
    @DisplayName("model 角色历史事件带草稿标记时重建为带 reasoningContent 元数据的 AssistantMessage")
    public void test_model_history_with_draft() {
        Content modelContent = Content.builder().role("model")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("历史推理") + "历史正文")))
                .build();

        Prompt prompt = assembler(null).toPrompt(
                LlmRequest.builder().model("m").contents(List.of(userText("问"), modelContent)).build());
        List<Message> messages = prompt.getInstructions();

        assertEquals(2, messages.size());
        assertEquals(MessageType.USER, messages.get(0).getMessageType());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("历史正文", assistant.getText());
        assertEquals("历史推理", assistant.getMetadata().get(ReasoningDraftProtocol.REASONING_METADATA_KEY));
    }

    @Test
    @DisplayName("assistant 是 model 的同义角色；未知角色按 user 处理")
    public void test_role_aliases_and_fallback() {
        Content assistantContent = Content.builder().role("assistant")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("r") + "正文"))).build();
        Content unknownRole = Content.builder().role("tool")
                .parts(List.of(Part.fromText("未知角色的文本"))).build();

        Prompt prompt = assembler(null).toPrompt(LlmRequest.builder().model("m")
                .contents(List.of(assistantContent, unknownRole)).build());
        List<Message> messages = prompt.getInstructions();

        assertEquals(2, messages.size());
        assertInstanceOf(AssistantMessage.class, messages.get(0), "assistant 走历史重建");
        assertInstanceOf(UserMessage.class, messages.get(1), "未知角色降级为 user 内容");
        assertEquals("未知角色的文本", messages.get(1).getText());
    }

    @Test
    @DisplayName("历史里带 FunctionCall 的 model 事件必须保留（tool 消息配对的另一半）")
    public void test_history_keeps_function_call_event() {
        Content modelWithToolCall = Content.builder().role("model")
                .parts(List.of(Part.builder().functionCall(FunctionCall.builder()
                        .id("call-7").name("executeCommand").args(Map.of("command", "df -h")).build()).build()))
                .build();

        Prompt prompt = assembler(null).toPrompt(LlmRequest.builder().model("m")
                .contents(List.of(userText("看磁盘"), modelWithToolCall,
                        functionResponseContent("call-7", "executeCommand", Map.of("result", "ok"))))
                .build());
        List<Message> messages = prompt.getInstructions();

        assertEquals(3, messages.size(), "user + assistant(tool_calls) + tool 三条缺一不可");
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("call-7", assistant.getToolCalls().get(0).id());
        ToolResponseMessage toolMessage = assertInstanceOf(ToolResponseMessage.class, messages.get(2));
        assertEquals(assistant.getToolCalls().get(0).id(), toolMessage.getResponses().get(0).id(),
                "tool_call_id 必须与 assistant 的 tool_calls 一致，否则 provider 400");
    }

    @Test
    @DisplayName("无草稿标记的纯文本历史 model 事件被跳过")
    public void test_history_plain_text_skipped() {
        Content modelContent = Content.builder().role("model")
                .parts(List.of(Part.fromText("没有草稿的旧回复"))).build();

        Prompt prompt = assembler(null).toPrompt(LlmRequest.builder().model("m")
                .contents(List.of(userText("问"), modelContent)).build());

        assertEquals(1, prompt.getInstructions().size(), "只有 user 消息留下");
    }

    // ===== 工具 options =====

    @Test
    @DisplayName("有工具时 per-call options 保留模型默认 options 的具体子类并挂上工具回调")
    public void test_tool_options_keep_concrete_type() {
        ToolCallingChatOptions modelDefaults = DefaultToolCallingChatOptions.builder()
                .model("fake-model").temperature(0.3).build();

        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(userText("执行 ls")))
                .tools(Map.of("fakeTool", new FakeAdkTool("fakeTool")))
                .build();

        Prompt prompt = assembler(modelDefaults).toPrompt(request);
        ChatOptions options = prompt.getOptions();

        ToolCallingChatOptions toolOptions = assertInstanceOf(ToolCallingChatOptions.class, options,
                "provider 会把 options 强转成自己的子类，必须是 ToolCallingChatOptions 而非通用 options");
        assertInstanceOf(DefaultToolCallingChatOptions.class, toolOptions);
        assertEquals("fake-model", toolOptions.getModel(), "mutate() 必须保留默认 options 的参数");
        assertEquals(0.3, toolOptions.getTemperature().doubleValue());
        assertEquals(1, toolOptions.getToolCallbacks().size());
        assertEquals("fakeTool", toolOptions.getToolCallbacks().get(0).getToolDefinition().name());
    }

    @Test
    @DisplayName("没有工具时 per-call options 为 null（让 provider 使用默认 options）")
    public void test_no_tools_yields_null_options() {
        ToolCallingChatOptions modelDefaults = DefaultToolCallingChatOptions.builder().model("fake-model").build();

        Prompt prompt = assembler(modelDefaults).toPrompt(LlmRequest.builder()
                .model("m").contents(List.of(userText("你好"))).build());

        assertNull(prompt.getOptions(), "无工具轮次不得下发 options，否则 provider 不会合并默认值");
    }

    @Test
    @DisplayName("模型默认 options 不支持工具时退化为通用 ToolCallingChatOptions（兜底分支）")
    public void test_tool_options_fallback_for_non_tool_capable_model() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(userText("执行 ls")))
                .tools(Map.of("fakeTool", new FakeAdkTool("fakeTool")))
                .build();

        Prompt prompt = assembler(ChatOptions.builder().model("plain-model").build()).toPrompt(request);
        ToolCallingChatOptions toolOptions = assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
        assertEquals(1, toolOptions.getToolCallbacks().size());
        assertFalse(prompt.getInstructions().isEmpty());
    }

    @Test
    @DisplayName("工具声明的 name/description 会带进 Spring AI 工具定义")
    public void test_tool_definition_carries_name_and_description() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .contents(List.of(userText("执行 ls")))
                .tools(Map.of("executeCommand", new FakeAdkTool("executeCommand")))
                .build();

        Prompt prompt = assembler(DefaultToolCallingChatOptions.builder().model("m").build()).toPrompt(request);
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        assertEquals("executeCommand", options.getToolCallbacks().get(0).getToolDefinition().name());
        assertNotNull(options.getToolCallbacks().get(0).getToolDefinition().description());
    }

    @Test
    @DisplayName("无 system、无历史时只有 user 消息，不产生多余空消息")
    public void test_minimal_prompt() {
        Prompt prompt = assembler(null).toPrompt(LlmRequest.builder()
                .model("m").contents(List.of(userText("hi"))).build());

        assertEquals(1, prompt.getInstructions().size());
        assertEquals("hi", prompt.getInstructions().get(0).getText());
        assertTrue(prompt.getSystemMessages().isEmpty());
    }

    @Test
    @DisplayName("通道 access 可覆盖历史消息载体（专有子类优先于通用元数据构造）")
    public void test_history_uses_channel_access() {
        ReasoningDraftAccess access = new ReasoningDraftAccess() {
            @Override
            public String readDraft(AssistantMessage message) {
                return null;
            }

            @Override
            public AssistantMessage buildHistoryMessage(String visibleText, String reasoning,
                                                        List<AssistantMessage.ToolCall> toolCalls) {
                return new AssistantMessage("CHANNEL:" + visibleText);
            }
        };
        SpringAiPromptAssembler withAccess = new SpringAiPromptAssembler(new FakeChatModel(null), protocol, access);

        Content modelContent = Content.builder().role("model")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("推理") + "正文"))).build();
        Prompt prompt = withAccess.toPrompt(LlmRequest.builder().model("m")
                .contents(List.of(modelContent)).build());

        assertEquals("CHANNEL:正文", prompt.getInstructions().get(0).getText());
    }
}

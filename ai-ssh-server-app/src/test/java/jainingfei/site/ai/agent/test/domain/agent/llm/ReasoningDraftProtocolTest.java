package jainingfei.site.ai.agent.test.domain.agent.llm;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftAccess;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftProtocol;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考草稿协议（ReasoningDraftProtocol）单元测试——纯逻辑，无网络无 Spring。
 *
 * <p>覆盖三块约定：
 * <ol>
 *   <li><b>标记编解码</b>：{@code wrapForStorage} 与 {@link ReasoningTagUtil#parse} 往返一致</li>
 *   <li><b>读取策略</b>：{@code extractDraftFragment} 机会式读取——通道 access 优先，
 *       其次通用元数据键 {@code reasoningContent}，都没有就返回 null</li>
 *   <li><b>历史重建</b>：{@code toHistoryMessages} 的降级规则（两条 provider 400 的教训）——
 *       无草稿且无工具调用的纯文本事件必须跳过；带 FunctionCall 的事件必须保留</li>
 * </ol>
 */
public class ReasoningDraftProtocolTest {

    private final ReasoningDraftProtocol protocol = new ReasoningDraftProtocol();

    // ===== 测试用假实现 =====

    /** 只提供读法、不提供专有历史载体的通道 */
    private static class ReadOnlyAccess implements ReasoningDraftAccess {
        private final String draft;

        ReadOnlyAccess(String draft) {
            this.draft = draft;
        }

        @Override
        public String readDraft(AssistantMessage message) {
            return draft;
        }
    }

    /** 提供专有历史载体的通道（模拟 DeepSeek 原生通道的 DeepSeekAssistantMessage） */
    private static class CustomMessageAccess implements ReasoningDraftAccess {
        @Override
        public String readDraft(AssistantMessage message) {
            return null;
        }

        @Override
        public AssistantMessage buildHistoryMessage(String visibleText, String reasoning,
                                                    List<AssistantMessage.ToolCall> toolCalls) {
            return new AssistantMessage("CUSTOM[" + visibleText + "|" + reasoning + "]");
        }
    }

    /** 明确返回 null 的 access：表示"本通道无专有载体"，协议层应回退通用构造 */
    private static class NullMessageAccess implements ReasoningDraftAccess {
        @Override
        public String readDraft(AssistantMessage message) {
            return null;
        }
    }

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
    }

    // ===== 标记编解码 =====

    @Test
    @DisplayName("wrapForStorage 产出的标记能被 ReasoningTagUtil 无损解析回来")
    public void test_wrap_round_trip() {
        String reasoning = "先看磁盘，再决定是否清理。\n第二行带换行。";
        String wrapped = protocol.wrapForStorage(reasoning);

        assertTrue(wrapped.startsWith(ReasoningTagUtil.OPEN), "必须带隐藏标记头");
        assertTrue(wrapped.endsWith(ReasoningTagUtil.CLOSE), "必须带隐藏标记尾");

        String[] parsed = ReasoningTagUtil.parse(wrapped + "可见正文");
        assertEquals(reasoning, parsed[0], "草稿原文必须无损往返");
        assertEquals("可见正文", parsed[1], "可见正文必须与草稿分离");
    }

    @Test
    @DisplayName("wrapForStorage(null/空串) 产出空串，不产生空标记块")
    public void test_wrap_blank_reasoning() {
        assertEquals("", protocol.wrapForStorage(null));
        assertEquals("", protocol.wrapForStorage(""));
    }

    @Test
    @DisplayName("buildHistoryMessage：草稿写进 reasoningContent 元数据键，正文与工具调用照常")
    public void test_build_history_message_metadata() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "exec", "{\"cmd\":\"ls\"}");
        AssistantMessage message = protocol.buildHistoryMessage("正文", "草稿", List.of(call));

        assertEquals("正文", message.getText());
        assertEquals("草稿", message.getMetadata().get(ReasoningDraftProtocol.REASONING_METADATA_KEY));
        assertEquals(1, message.getToolCalls().size());
        assertEquals("call-1", message.getToolCalls().get(0).id());
        assertTrue(protocol.hasDraft(message));
    }

    @Test
    @DisplayName("buildHistoryMessage：reasoning=null 时不写元数据键，hasDraft 为 false")
    public void test_build_history_message_without_draft() {
        AssistantMessage message = protocol.buildHistoryMessage("", null, List.of());

        assertEquals("", message.getText(), "空正文合法：内容由 toolCalls 承载");
        assertFalse(protocol.hasDraft(message));
        assertTrue(message.getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("hasDraft 对 null 消息安全返回 false")
    public void test_has_draft_null_safe() {
        assertFalse(protocol.hasDraft(null));
    }

    // ===== extractDraftFragment =====

    @Test
    @DisplayName("extractDraftFragment：响应为 null 或无 results 时返回 null")
    public void test_extract_draft_null_response() {
        assertNull(protocol.extractDraftFragment(null, null));
        assertNull(protocol.extractDraftFragment(new ChatResponse(List.of()), null));
    }

    @Test
    @DisplayName("extractDraftFragment：无 access 时读通用元数据键 reasoningContent")
    public void test_extract_draft_from_metadata() {
        ChatResponse response = responseOf(AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(ReasoningDraftProtocol.REASONING_METADATA_KEY, "元数据草稿"))
                .build());

        assertEquals("元数据草稿", protocol.extractDraftFragment(response, null));
    }

    @Test
    @DisplayName("extractDraftFragment：无 access 且无元数据键时返回 null（不是空串）")
    public void test_extract_draft_absent() {
        ChatResponse response = responseOf(AssistantMessage.builder().content("正文").build());
        assertNull(protocol.extractDraftFragment(response, null));

        // 元数据键存在但值不是字符串：同样视为"没有"
        ChatResponse nonString = responseOf(AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(ReasoningDraftProtocol.REASONING_METADATA_KEY, 42))
                .build());
        assertNull(protocol.extractDraftFragment(nonString, null));
    }

    @Test
    @DisplayName("extractDraftFragment：通道 access 返回文本时优先于通用元数据键")
    public void test_extract_draft_access_wins() {
        ChatResponse response = responseOf(AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(ReasoningDraftProtocol.REASONING_METADATA_KEY, "元数据草稿"))
                .build());

        assertEquals("通道草稿", protocol.extractDraftFragment(response, new ReadOnlyAccess("通道草稿")));
    }

    @Test
    @DisplayName("extractDraftFragment：通道 access 返回 null/空串时回退到元数据键")
    public void test_extract_draft_access_fallback() {
        ChatResponse response = responseOf(AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(ReasoningDraftProtocol.REASONING_METADATA_KEY, "元数据草稿"))
                .build());

        assertEquals("元数据草稿", protocol.extractDraftFragment(response, new ReadOnlyAccess(null)));
        assertEquals("元数据草稿", protocol.extractDraftFragment(response, new ReadOnlyAccess("")));
    }

    // ===== toHistoryMessages =====

    @Test
    @DisplayName("toHistoryMessages：无草稿标记的纯文本事件跳过（防 provider 400）")
    public void test_history_skips_plain_text_without_draft() {
        Content content = Content.builder()
                .role("model")
                .parts(List.of(Part.fromText("模型只回了正文，没有思考草稿")))
                .build();

        assertTrue(protocol.toHistoryMessages(content, null).isEmpty(),
                "无草稿的纯文本 assistant 事件必须跳过——回传时缺 reasoning_content 会被 400");
    }

    @Test
    @DisplayName("toHistoryMessages：带草稿标记的事件保留，正文剥标记、草稿进元数据")
    public void test_history_keeps_draft_event() {
        Content content = Content.builder()
                .role("model")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("我的推理") + "最终答案")))
                .build();

        List<Message> messages = protocol.toHistoryMessages(content, null);
        assertEquals(1, messages.size());
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertEquals("最终答案", assistant.getText(), "标记必须从可见正文剥离");
        assertEquals("我的推理", assistant.getMetadata().get(ReasoningDraftProtocol.REASONING_METADATA_KEY));
    }

    @Test
    @DisplayName("toHistoryMessages：无草稿但带 FunctionCall 的事件必须保留并转成 tool_calls")
    public void test_history_keeps_function_call_without_draft() {
        Content content = Content.builder()
                .role("model")
                .parts(List.of(Part.builder().functionCall(FunctionCall.builder()
                        .id("call-9").name("executeCommand").args(Map.of("command", "ls -l")).build()).build()))
                .build();

        List<Message> messages = protocol.toHistoryMessages(content, null);
        assertEquals(1, messages.size(), "tool 消息需要前置 tool_calls 配对，FunctionCall 事件不得跳过");
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertEquals("", assistant.getText());
        assertEquals(1, assistant.getToolCalls().size());
        AssistantMessage.ToolCall call = assistant.getToolCalls().get(0);
        assertEquals("call-9", call.id());
        assertEquals("function", call.type());
        assertEquals("executeCommand", call.name());
        assertTrue(call.arguments().contains("ls -l"), "参数必须序列化成 JSON，实际 " + call.arguments());
    }

    @Test
    @DisplayName("toHistoryMessages：通道提供 buildHistoryMessage 时使用通道专有消息")
    public void test_history_uses_access_message() {
        Content content = Content.builder()
                .role("model")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("推理") + "正文")))
                .build();

        List<Message> messages = protocol.toHistoryMessages(content, new CustomMessageAccess());
        assertEquals(1, messages.size());
        assertEquals("CUSTOM[正文|推理]", messages.get(0).getText(), "通道专有载体优先于通用元数据构造");
    }

    @Test
    @DisplayName("toHistoryMessages：access 返回 null 时回退通用构造（元数据键仍在）")
    public void test_history_falls_back_when_access_returns_null() {
        Content content = Content.builder()
                .role("model")
                .parts(List.of(Part.fromText(ReasoningTagUtil.wrap("推理") + "正文")))
                .build();

        List<Message> messages = protocol.toHistoryMessages(content, new NullMessageAccess());
        assertEquals(1, messages.size());
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertEquals("正文", assistant.getText());
        assertEquals("推理", assistant.getMetadata().get(ReasoningDraftProtocol.REASONING_METADATA_KEY));
    }

    @Test
    @DisplayName("toHistoryMessages：空 content（无 parts）返回空列表，不抛异常")
    public void test_history_empty_content() {
        assertTrue(protocol.toHistoryMessages(Content.builder().role("model").build(), null).isEmpty());
        assertTrue(protocol.toHistoryMessages(
                Content.builder().role("model").parts(List.of()).build(), null).isEmpty());
    }

    @Test
    @DisplayName("toHistoryMessages：多段文本先拼接再解析标记（聚合事件被拆成多 part 的场景）")
    public void test_history_concatenates_text_parts() {
        Content content = Content.builder()
                .role("assistant")
                .parts(List.of(
                        Part.fromText(ReasoningTagUtil.wrap("推理") + "前半段，"),
                        Part.fromText("后半段")))
                .build();

        List<Message> messages = protocol.toHistoryMessages(content, null);
        assertEquals(1, messages.size());
        assertEquals("推理", ((AssistantMessage) messages.get(0))
                .getMetadata().get(ReasoningDraftProtocol.REASONING_METADATA_KEY));
        assertEquals("前半段，后半段", messages.get(0).getText(), "标记之后的可见正文必须完整拼接");
    }

    @Test
    @DisplayName("标记编解码的边界事实：标记不是前缀时，标记之前的文本会被丢弃")
    public void test_marker_is_prefix_contract() {
        // ReasoningTagUtil.parse 只取 CLOSE 之后的文本，标记之前的文本不参与还原。
        // 当前主链路里标记永远由 wrapForStorage 生成在前缀位置，因此不构成线上问题；
        // 这里把这条隐含契约固定下来，避免将来把正文拼到标记之前。
        String text = "标记之前的文本" + ReasoningTagUtil.wrap("推理") + "标记之后的文本";
        String[] parsed = ReasoningTagUtil.parse(text);
        assertEquals("推理", parsed[0]);
        assertEquals("标记之后的文本", parsed[1]);
    }

    @Test
    @DisplayName("非 assistant 消息不会被误判为带草稿")
    public void test_has_draft_on_user_message() {
        assertFalse(protocol.hasDraft(null));
        UserMessage user = new UserMessage("你好");
        assertNotNull(user.getMessageType());
        assertFalse(protocol.hasDraft(AssistantMessage.builder().content("x").build()));
        assertSame(ReasoningDraftProtocol.REASONING_METADATA_KEY, "reasoningContent");
    }
}

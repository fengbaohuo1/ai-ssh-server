package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 思考草稿协议——"推理内容"在 ADK 事件文本与 Spring AI 消息之间的读写约定。
 *
 * <pre>
 *   落库：模型响应里的推理内容 → 隐藏标记（{@code <<DSREASON::base64::DSREASON>>}）随聚合事件入库
 *         —— 标记对模型不可见，字节格式由 {@link ReasoningTagUtil} 定义（唯一 codec）
 *   回放：重建历史时解析标记 → 写进 {@link AssistantMessage} 的元数据
 *         —— 协议层（如 spring-ai-openai）据此把 {@code reasoning_content} 回写给服务端
 * </pre>
 *
 * <p>为什么回放走元数据而不是自定义消息类型：元数据是 Spring AI 的通用出口，
 * 各家 provider 模块自己决定怎么把它变成线上字段；调用方因此不必认识任何厂商类型。
 *
 * <p>与 {@link ReasoningDraftAccess} 的分工：本类只管"约定与策略"（标记怎么读写、历史怎么重建），
 * 具体读法（元数据键 / 厂商专有消息类型）由通道档案通过 access 提供；通道不提供时降级为空。
 */
@Slf4j
public class ReasoningDraftProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认的推理内容元数据键（spring-ai 的 OpenAI 协议通道读写该键） */
    public static final String REASONING_METADATA_KEY = "reasoningContent";

    /**
     * 从一次模型响应中取出推理内容分片；本通道不提供时返回 {@code null}。
     * <p>机会式读取：只要响应携带推理内容就取，通道能力位只用于装配期告警与展示。
     */
    public String extractDraftFragment(ChatResponse response, ReasoningDraftAccess access) {
        if (response == null || response.getResults().isEmpty()) {
            return null;
        }
        AssistantMessage message = response.getResult().getOutput();
        if (access != null) {
            String fragment = access.readDraft(message);
            if (fragment != null && !fragment.isEmpty()) {
                return fragment;
            }
        }
        // 默认约定：元数据键（不依赖具体通道，通用出口）
        Object raw = message.getMetadata().get(REASONING_METADATA_KEY);
        return raw instanceof String text && !text.isEmpty() ? text : null;
    }

    /**
     * 构造历史 assistant 消息：草稿写进元数据（协议层回放），正文与工具调用照常。
     *
     * @param visibleText 可见正文（空串合法：内容由 toolCalls 承载）
     * @param reasoning   草稿，可为 null
     * @param toolCalls   工具调用，可为空
     */
    public AssistantMessage buildHistoryMessage(String visibleText, String reasoning,
                                                List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(visibleText == null ? "" : visibleText);
        if (reasoning != null) {
            builder.properties(Map.of(REASONING_METADATA_KEY, reasoning));
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    /** 历史 assistant 消息是否带草稿（请求形状诊断用） */
    public boolean hasDraft(AssistantMessage message) {
        return message != null && message.getMetadata().get(REASONING_METADATA_KEY) != null;
    }

    /**
     * 历史 assistant 事件 → Spring AI 消息列表（0 或 1 条）。
     *
     * <p>降级策略（V3 实测校准）：模型并非每次调用都产出推理内容——
     * 无草稿标记的纯文本事件跳过（防"原响应有草稿却未回传"的 400）；
     * 但<b>携带 FunctionCall 的事件必须保留</b>（tool 消息要与前置 tool_calls 配对），
     * 无标记时构造不带草稿的消息即可。
     */
    public List<Message> toHistoryMessages(Content content) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                text.append(part.text().get());
            } else if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                toolCalls.add(new AssistantMessage.ToolCall(
                        functionCall.id().orElse(""),
                        "function",
                        functionCall.name().orElse(""),
                        functionCall.args().map(args -> {
                            try { return MAPPER.writeValueAsString(args); } catch (Exception e) { return "{}"; }
                        }).orElse("{}")));
            }
        }

        String[] parsed = ReasoningTagUtil.parse(text.toString());
        String reasoning = parsed[0];
        String visible = parsed[1] == null ? "" : parsed[1];

        if (reasoning == null && toolCalls.isEmpty()) {
            return List.of();
        }
        return List.of(buildHistoryMessage(visible, reasoning, toolCalls));
    }

    /** 把草稿包装成事件文本前缀（落库用） */
    public String wrapForStorage(String reasoning) {
        return ReasoningTagUtil.wrap(reasoning);
    }
}

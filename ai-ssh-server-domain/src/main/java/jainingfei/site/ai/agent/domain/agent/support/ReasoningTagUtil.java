package jainingfei.site.ai.agent.domain.agent.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 思考草稿（provider 的 reasoning_content）的"事件文本内隐藏标记"编解码。
 *
 * <p>背景：显式 ReAct 下，assistant 消息以纯文本事件存于 ADK 会话，草稿没有字段位置。
 * 为支持 L2 草稿延续（思考模型 + 工具要求历史 assistant 原样回传 reasoning_content），
 * 草稿以隐藏标记块随 assistant 事件文本落库，重建请求历史时再解析出来放回
 * {@code AssistantMessage} 的 reasoningContent 元数据。标记块对模型不可见（发送前剥离），
 * 对 SSE/DB（展示与落库文本）也不可见（AiCallNode 展示前剥离）。
 *
 * <p>格式（base64 URL 安全、无换行，防正文/截断干扰）：
 * {@code <<DSREASON::<base64>::DSREASON>>\n + 可见文本}
 */
public final class ReasoningTagUtil {

    /** 隐藏标记头/尾 */
    public static final String OPEN = "<<DSREASON::";
    public static final String CLOSE = "::DSREASON>>\n";

    /** 草稿存储上限（字符）；思考草稿通常数千字符 */
    public static final int MAX_REASONING_CHARS = 12_000;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private ReasoningTagUtil() {
    }

    /** 用隐藏标记包裹草稿文本（含尾部换行，与可见文本分隔） */
    public static String wrap(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return "";
        }
        if (reasoning.length() > MAX_REASONING_CHARS) {
            reasoning = reasoning.substring(0, MAX_REASONING_CHARS);
        }
        return OPEN + B64.encodeToString(reasoning.getBytes(StandardCharsets.UTF_8)) + CLOSE;
    }

    /**
     * 解析事件文本：返回 [reasoning 原文, 剥离标记后的可见文本]；
     * 无标记或解码失败时返回 [null, 原文本]（调用方按"无草稿"降级处理）。
     */
    public static String[] parse(String text) {
        if (text == null) {
            return new String[] { null, null };
        }
        int start = text.indexOf(OPEN);
        if (start < 0) {
            return new String[] { null, text };
        }
        int contentStart = start + OPEN.length();
        int end = text.indexOf(CLOSE, contentStart);
        if (end < 0) {
            // 标记不完整（被截断等）——整体视为不可用，避免半截草稿误用
            return new String[] { null, text };
        }
        try {
            String reasoning = new String(B64D.decode(text.substring(contentStart, end)), StandardCharsets.UTF_8);
            String visible = text.substring(end + CLOSE.length());
            return new String[] { reasoning, visible };
        } catch (IllegalArgumentException e) {
            return new String[] { null, text };
        }
    }

    /** 剥离隐藏标记（SSE 展示 / DB 落库前调用）；无标记时原样返回 */
    public static String strip(String text) {
        return parse(text)[1];
    }
}

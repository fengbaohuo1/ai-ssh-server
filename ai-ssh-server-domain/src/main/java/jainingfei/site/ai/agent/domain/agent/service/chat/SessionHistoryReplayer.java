package jainingfei.site.ai.agent.domain.agent.service.chat;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.support.TextRetentionUtil;
import jainingfei.site.ai.agent.domain.agent.model.valobj.properties.SessionGovernanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 会话历史回顾回放器（P0 冷启动一致性）——重启后把 DB 里的对话以"user 角色回顾文本"
 * 注入重建的空 ADK 会话，让模型第一回合就能看到上一轮说过什么、跑过什么命令。
 *
 * <p>为什么是"user 回顾文本"而不是逐条全真事件回放（约束见 SpringAiLlmAdapter / ReasoningDraftProtocol）：
 * <ul>
 *   <li>无草稿标记的纯文本 assistant 事件在历史重建时被整条跳过（思考模型防 400）——
 *       直接回放 DB 的可见文本 assistant 行会被滤掉；</li>
 *   <li>带 FunctionCall 的 assistant 事件需与 tool 消息配对，而 DB 只存可见文本，
 *       FC 参数与推理草稿均未落库，重建会破坏配对或触发 400；</li>
 *   <li>故以 user 角色承载带角色标签的回顾文本，绕开上述两类约束。</li>
 * </ul>
 *
 * <p>窗口策略：只回放<b>尾部最近一段</b>（字符预算），不回放远古全量——避免重启后把
 * 运行期已被压缩折叠的中远古事件"复活成全文"，也避免首回合 token 暴涨。
 *
 * <p>旁路语义：读库/构造/注入任一步异常仅 warn，不影响对话可用性（退化为现状空会话）。
 */
@Slf4j
@Component
public class SessionHistoryReplayer {

    /** 回顾块标题 */
    public static final String REVIEW_HEADER = "[会话历史回顾]";

    /** 从 DB 拉取的候选消息条数上限（由字符预算决定实际截取窗口） */
    private static final int MAX_CANDIDATE_MESSAGES = 200;

    /** 单条工具行输出保留上限（head/tail） */
    private static final int TOOL_LINE_MAX_CHARS = 400;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    /** 会话治理参数（回顾预算 + user 事件上限） */
    @Resource
    private SessionGovernanceProperties governance;

    /** 回顾总预算（字符） */
    private int replayBudgetChars() {
        return governance.getColdStart().getReplayBudgetChars();
    }

    /** 单条 user 事件上限（须小于 user 事件净化上限 max-user-text，避免被净化器截断） */
    private int maxUserText() {
        return governance.getMaxUserText();
    }

    /**
     * 重启重建空会话后调用：读最近消息 → 构造回顾文本 → 按单事件上限分块 →
     * 逐块以 user 事件追加进会话。仅应由"空快照重建"分支触发一次。
     */
    public void replayIntoSession(Runner runner, String appName, String userId, String sessionId) {
        if (runner == null || sessionId == null) {
            return;
        }
        try {
            Session session = runner.sessionService()
                    .getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
            if (session == null) {
                log.warn("历史回顾跳过：会话不存在 sessionId={}", sessionId);
                return;
            }

            List<ChatMessageEntity> recent = chatHistoryRepository.getRecentMessages(
                    sessionId, MAX_CANDIDATE_MESSAGES);
            String reviewText = buildReviewText(recent, Math.max(replayBudgetChars(), 0));
            if (reviewText.isBlank()) {
                return;
            }

            // 单 user 事件受 sanitizeUserEvent 的 max-user-text 限长约束：分块注入
            int chunkChars = Math.max(maxUserText() - 300, 800);
            List<String> chunks = chunkText(reviewText, chunkChars);
            int chunkIndex = 0;
            for (String chunk : chunks) {
                if (chunkIndex > 0) {
                    chunk = "[会话历史回顾(续)]\n" + chunk;
                }
                Event event = Event.builder()
                        .id(Event.generateEventId())
                        .author("user")
                        .content(Content.builder()
                                .role("user")
                                .parts(List.of(Part.fromText(chunk)))
                                .build())
                        .build();
                runner.sessionService().appendEvent(session, event).blockingGet();
                chunkIndex++;
            }
            log.info("会话历史回顾注入完成 sessionId={} 消息 {} 条 -> 回顾 {} 字符 / {} 块",
                    sessionId, recent.size(), reviewText.length(), chunks.size());
        } catch (Exception e) {
            log.warn("会话历史回顾注入失败（旁路，不影响主流程）sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 纯函数：DB 消息列表 → 带角色标签的回顾文本。
     * <p>窗口语义：输入为"最近 N 条（时间正序）"，从尾部（最新）往前累计，直到字符预算
     * 耗尽——保证保留的是最近的对话；单条超预算的巨行跳过（不阻断更早的更小行）。
     */
    public static String buildReviewText(List<ChatMessageEntity> messages, int budgetChars) {
        if (messages == null || messages.isEmpty() || budgetChars <= 0) {
            return "";
        }
        // 先转行并过滤无效行
        List<String> lines = new ArrayList<>();
        for (ChatMessageEntity msg : messages) {
            String line = toReviewLine(msg);
            if (line != null && !line.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        // 从最新往最旧累计（保持最近优先），保留顺序随后反转回来
        List<String> kept = new ArrayList<>();
        int remaining = budgetChars;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            int cost = line.length() + 1;
            if (cost > remaining) {
                continue; // 单条超预算：跳过该行（巨行粘贴等罕见场景）
            }
            kept.add(0, line);
            remaining -= cost;
        }
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(REVIEW_HEADER).append("\n");
        for (String line : kept) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /** 单条消息 → 一行回顾（user/assistant 原文；tool 只留工具名 + head/tail 输出） */
    private static String toReviewLine(ChatMessageEntity msg) {
        if (msg == null) {
            return null;
        }
        String role = msg.getRole() == null ? "" : msg.getRole().toLowerCase();
        String content = msg.getContent() == null ? "" : msg.getContent();
        switch (role) {
            case "user":
                return content.isBlank() ? null : "用户: " + content;
            case "assistant":
                return content.isBlank() ? null : "助手: " + content;
            case "tool": {
                String name = msg.getToolName() == null ? "tool" : msg.getToolName();
                String summary = content.isBlank() ? "(无输出)" : content;
                return "[工具] " + name + ": " + TextRetentionUtil.retainHeadTail(summary, TOOL_LINE_MAX_CHARS);
            }
            default:
                return null;
        }
    }

    /** 按字符上限切块，尽量在换行处断开（超长单行硬切） */
    public static List<String> chunkText(String text, int chunkChars) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (text.length() <= chunkChars) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.length() >= chunkChars) {
                // 超长单行（罕见）：先冲刷当前块，再按上限硬切该行
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < line.length(); i += chunkChars) {
                    chunks.add(line.substring(i, Math.min(i + chunkChars, line.length())));
                }
                continue;
            }
            if (current.length() + line.length() + 1 > chunkChars && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

}

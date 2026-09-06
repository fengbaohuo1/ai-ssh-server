package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.service.chat.SessionHistoryReplayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话历史回顾（SessionHistoryReplayer）单元测试——纯函数驱动，无网络无 Spring。
 * <p>
 * 覆盖：角色标签构造、工具行 head/tail 截断、尾部窗口预算语义（保留最近）、
 * 巨行跳过、分块切分（换行断开/超长单行硬切）、空历史。
 */
public class SessionHistoryReplayerTest {

    private ChatMessageEntity msg(String role, String content, String toolName) {
        return ChatMessageEntity.builder()
                .sessionId("s1")
                .role(role)
                .content(content)
                .toolName(toolName)
                .build();
    }

    @Test
    public void test_empty_or_null_input() {
        assertEquals("", SessionHistoryReplayer.buildReviewText(null, 12000));
        assertEquals("", SessionHistoryReplayer.buildReviewText(List.of(), 12000));
        assertEquals("", SessionHistoryReplayer.buildReviewText(List.of(msg("user", "hi", null)), 0));
    }

    @Test
    public void test_build_with_roles_and_tool_cap() {
        List<ChatMessageEntity> messages = new ArrayList<>();
        messages.add(msg("user", "帮我查磁盘", null));
        messages.add(msg("assistant", "已执行 df -h", null));
        String longOutput = "A".repeat(3000);
        messages.add(msg("tool", longOutput + "\n[success=true]", "executeCommand"));

        String text = SessionHistoryReplayer.buildReviewText(messages, 12000);
        assertTrue(text.startsWith(SessionHistoryReplayer.REVIEW_HEADER + "\n"));
        assertTrue(text.contains("用户: 帮我查磁盘"));
        assertTrue(text.contains("助手: 已执行 df -h"));
        assertTrue(text.contains("[工具] executeCommand:"));
        // 工具输出 head/tail 上限 400
        assertTrue(text.length() < 1200, "工具行应被 head/tail 截断，实际长度 " + text.length());
    }

    @Test
    public void test_budget_keeps_most_recent() {
        List<ChatMessageEntity> messages = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            messages.add(msg("user", "消息" + i + "：" + "x".repeat(200), null));
        }
        // 预算只够最近 4 条左右（每条约 205 字符）
        String text = SessionHistoryReplayer.buildReviewText(messages, 900);
        assertFalse(text.contains("消息1"), "预算窗口应从最新往回保留，最旧消息不应出现");
        assertTrue(text.contains("消息30"), "最新消息必须保留");
        assertTrue(text.contains("消息27"), "接近最新的消息应保留");
    }

    @Test
    public void test_oversized_line_skipped_not_blocking() {
        List<ChatMessageEntity> messages = new ArrayList<>();
        messages.add(msg("user", "A".repeat(5000), null)); // 超预算巨行（粘贴场景）
        messages.add(msg("user", "最新小消息", null));
        String text = SessionHistoryReplayer.buildReviewText(messages, 1000);
        assertFalse(text.contains("AAAA"), "巨行应被跳过");
        assertTrue(text.contains("最新小消息"), "巨行不能阻断其后（更新）的小行");
    }

    @Test
    public void test_chunk_splits_on_line_boundary() {
        List<String> chunks = SessionHistoryReplayer.chunkText("a\nb\nc\nd", 6);
        assertEquals(2, chunks.size());
        assertEquals("a\nb\nc", chunks.get(0));
        assertEquals("d", chunks.get(1));
    }

    @Test
    public void test_chunk_hard_split_oversized_line() {
        String text = "x".repeat(100);
        List<String> chunks = SessionHistoryReplayer.chunkText(text, 30);
        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 30), "每块不得超过上限");
        // 拼接不回丢内容
        assertEquals(text, String.join("", chunks));
    }

    @Test
    public void test_chunk_small_text_single() {
        List<String> chunks = SessionHistoryReplayer.chunkText("短文本", 500);
        assertEquals(1, chunks.size());
        assertEquals("短文本", chunks.get(0));
    }

    @Test
    public void test_unknown_role_ignored() {
        List<ChatMessageEntity> messages = List.of(
                msg("user", "你好", null),
                msg("system", "某系统行", null));
        String text = SessionHistoryReplayer.buildReviewText(messages, 12000);
        assertTrue(text.contains("用户: 你好"));
        assertFalse(text.contains("系统行"));
    }
}

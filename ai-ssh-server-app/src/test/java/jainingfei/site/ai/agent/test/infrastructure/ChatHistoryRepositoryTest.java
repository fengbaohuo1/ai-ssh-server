package jainingfei.site.ai.agent.test.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.infrastructure.dao.IChatMessageDao;
import jainingfei.site.ai.agent.infrastructure.dao.IChatMilestoneDao;
import jainingfei.site.ai.agent.infrastructure.dao.IChatSessionDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMessagePO;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMilestonePO;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatSessionPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话历史仓储集成测试（2-8）
 * <p>
 * 覆盖 chat_session / chat_message / chat_milestone 三张表的写入与查询闭环。
 * 仅依赖 MySQL（dev 库），不依赖外部模型 API。测试数据用专用前缀并在收尾清理。
 */
@Slf4j
@SpringBootTest
public class ChatHistoryRepositoryTest {

    private static final String SESSION_ID = "chathistory-test-session";
    private static final String USER_ID = "chathistory-test-user";
    private static final String AGENT_ID = "test-agent";

    @Resource
    private IChatHistoryRepository chatHistoryRepository;
    @Resource
    private IChatSessionDao chatSessionDao;
    @Resource
    private IChatMessageDao chatMessageDao;
    @Resource
    private IChatMilestoneDao chatMilestoneDao;

    @Test
    public void test_save_and_query_01() {
        log.info("========== 对话历史仓储测试开始 ==========");
        cleanup();

        // 1. 会话元数据：两次保存同 id 应幂等（重启重复创建不报错）
        chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                .id(SESSION_ID).agentId(AGENT_ID).userId(USER_ID)
                .title("新会话").messageCount(0).build());
        chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                .id(SESSION_ID).agentId(AGENT_ID).userId(USER_ID)
                .title("新会话").messageCount(0).build());
        List<ChatSessionEntity> sessions = chatHistoryRepository.querySessionList(AGENT_ID, USER_ID, 10);
        assertEquals(1, sessions.size(), "同 id 重复保存应幂等");
        log.info("会话元数据幂等保存 OK: {}", sessions.get(0).getId());

        // 2. 消息落库 + 查询（时间正序）+ message_count 原子递增
        chatHistoryRepository.saveMessage(msg("user", "帮我排查 nginx 502", null, null));
        chatHistoryRepository.saveMessage(msg("assistant", "结论：上游服务超时", null, null));
        chatHistoryRepository.saveMessage(msg("tool", "502 Bad Gateway", "executeCommand", "call_1"));

        List<ChatMessageEntity> recent = chatHistoryRepository.getRecentMessages(SESSION_ID, 10);
        assertEquals(3, recent.size(), "应查回 3 条消息");
        assertEquals("user", recent.get(0).getRole(), "时间正序：首条应为 user");
        assertEquals("tool", recent.get(2).getRole(), "时间正序：末条应为 tool");
        assertEquals("executeCommand", recent.get(2).getToolName());

        ChatSessionPO sessionPO = chatSessionDao.selectById(SESSION_ID);
        assertEquals(3, sessionPO.getMessageCount(), "message_count 应原子递增到 3");
        log.info("消息落库/正序查询/计数递增 OK: count={}", sessionPO.getMessageCount());

        // 3. 里程碑落库 + 查询（正序）
        chatHistoryRepository.saveMilestone(SESSION_ID, MilestoneVO.builder()
                .type(MilestoneVO.Type.TASK_CHANGE).content("不对，换个思路").timestamp(System.currentTimeMillis()).build());
        chatHistoryRepository.saveMilestone(SESSION_ID, MilestoneVO.builder()
                .type(MilestoneVO.Type.ERROR).content("nginx: [emerg] bind() failed").timestamp(System.currentTimeMillis()).build());
        List<MilestoneVO> milestones = chatHistoryRepository.getRecentMilestones(SESSION_ID, 10);
        assertEquals(2, milestones.size());
        assertEquals(MilestoneVO.Type.TASK_CHANGE, milestones.get(0).getType(), "里程碑应时间正序");
        assertTrue(milestones.get(1).getTimestamp() > 0);
        log.info("里程碑落库/正序查询 OK: {} 条", milestones.size());

        // 4. 消息列表查询
        List<ChatMessageEntity> messageList = chatHistoryRepository.queryMessageList(SESSION_ID, 100);
        assertEquals(3, messageList.size());

        log.info("========== 对话历史仓储测试通过 ==========");
    }

    private ChatMessageEntity msg(String role, String content, String toolName, String toolCallId) {
        return ChatMessageEntity.builder()
                .sessionId(SESSION_ID)
                .role(role)
                .content(content)
                .toolName(toolName)
                .toolCallId(toolCallId)
                .priority("HIGH".equals(role) || "tool".equals(role) ? "HIGH" : "MEDIUM")
                .tokenCount(content.length() / 2)
                .build();
    }

    private void cleanup() {
        chatMessageDao.delete(new LambdaQueryWrapper<ChatMessagePO>()
                .eq(ChatMessagePO::getSessionId, SESSION_ID));
        chatMilestoneDao.delete(new LambdaQueryWrapper<ChatMilestonePO>()
                .eq(ChatMilestonePO::getSessionId, SESSION_ID));
        chatSessionDao.delete(new LambdaQueryWrapper<ChatSessionPO>()
                .eq(ChatSessionPO::getUserId, USER_ID));
    }
}

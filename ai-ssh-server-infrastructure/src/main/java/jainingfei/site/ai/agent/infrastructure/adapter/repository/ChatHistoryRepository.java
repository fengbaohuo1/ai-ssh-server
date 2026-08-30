package jainingfei.site.ai.agent.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话历史仓储实现——chat_session / chat_message / chat_milestone 的持久化出口。
 *
 * <p>本类只做纯粹的读写，不吞异常：调用方（领域服务/节点）按"旁路写入"原则
 * 自行 try-catch，DB 失败不影响 Agent 主流程。
 */
@Slf4j
@Component
public class ChatHistoryRepository implements IChatHistoryRepository {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private IChatMilestoneDao chatMilestoneDao;

    @Override
    public void saveSession(ChatSessionEntity session) {
        // 按 id 幂等：服务重启后客户端可能带回旧 sessionId，重复创建不报错、不覆盖
        ChatSessionPO existPO = chatSessionDao.selectById(session.getId());
        if (existPO != null) {
            return;
        }
        ChatSessionPO po = new ChatSessionPO();
        po.setId(session.getId());
        po.setAgentId(session.getAgentId());
        po.setUserId(session.getUserId());
        po.setTitle(session.getTitle());
        po.setMessageCount(session.getMessageCount() != null ? session.getMessageCount() : 0);
        chatSessionDao.insert(po);
    }

    @Override
    public void saveMessage(ChatMessageEntity message) {
        ChatMessagePO po = toPO(message);
        chatMessageDao.insert(po);

        // 原子递增会话消息计数
        chatSessionDao.update(null, new LambdaUpdateWrapper<ChatSessionPO>()
                .eq(ChatSessionPO::getId, message.getSessionId())
                .setSql("message_count = message_count + 1"));
    }

    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        // id 自增即时间序：倒序取最近 N 条后反转，一次查询拿到"最近 N 条 + 正序"
        List<ChatMessagePO> poList = chatMessageDao.selectList(
                new LambdaQueryWrapper<ChatMessagePO>()
                        .eq(ChatMessagePO::getSessionId, sessionId)
                        .orderByDesc(ChatMessagePO::getId)
                        .last("LIMIT " + Math.max(limit, 1))
        );
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(poList);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public void saveMilestone(String sessionId, MilestoneVO milestoneVO) {
        ChatMilestonePO po = new ChatMilestonePO();
        po.setSessionId(sessionId);
        po.setType(milestoneVO.getType().name());
        po.setContent(milestoneVO.getContent());
        chatMilestoneDao.insert(po);
    }

    @Override
    public List<MilestoneVO> getRecentMilestones(String sessionId, int limit) {
        List<ChatMilestonePO> poList = chatMilestoneDao.selectList(
                new LambdaQueryWrapper<ChatMilestonePO>()
                        .eq(ChatMilestonePO::getSessionId, sessionId)
                        .orderByDesc(ChatMilestonePO::getId)
                        .last("LIMIT " + Math.max(limit, 1))
        );
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(poList);
        return poList.stream().map(this::toMilestoneVO).collect(Collectors.toList());
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        List<ChatSessionPO> poList = chatSessionDao.selectList(
                new LambdaQueryWrapper<ChatSessionPO>()
                        .eq(ChatSessionPO::getUserId, userId)
                        .eq(agentId != null && !agentId.isBlank(), ChatSessionPO::getAgentId, agentId)
                        .orderByDesc(ChatSessionPO::getUpdatedAt)
                        .last("LIMIT " + Math.max(limit, 1))
        );
        if (poList == null || poList.isEmpty()) {
            return Collections.emptyList();
        }
        return poList.stream().map(this::toSessionEntity).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return getRecentMessages(sessionId, limit);
    }

    // ==================== 转换方法 ====================

    private ChatMessagePO toPO(ChatMessageEntity entity) {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(entity.getSessionId());
        po.setRole(entity.getRole());
        po.setContent(entity.getContent());
        po.setToolName(entity.getToolName());
        po.setToolCallId(entity.getToolCallId());
        po.setPriority(entity.getPriority() != null ? entity.getPriority() : "MEDIUM");
        po.setTokenCount(entity.getTokenCount() != null ? entity.getTokenCount() : 0);
        return po;
    }

    private ChatMessageEntity toEntity(ChatMessagePO po) {
        return ChatMessageEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .role(po.getRole())
                .content(po.getContent())
                .toolName(po.getToolName())
                .toolCallId(po.getToolCallId())
                .priority(po.getPriority())
                .tokenCount(po.getTokenCount())
                .createdAt(po.getCreatedAt())
                .build();
    }

    private MilestoneVO toMilestoneVO(ChatMilestonePO po) {
        return MilestoneVO.builder()
                .type(MilestoneVO.Type.valueOf(po.getType()))
                .content(po.getContent())
                // DB 时间戳精度是秒，仅用于水位排序，不足毫秒时用当前时间兜底
                .timestamp(po.getCreatedAt() != null
                        ? java.sql.Timestamp.valueOf(po.getCreatedAt()).getTime()
                        : System.currentTimeMillis())
                .build();
    }

    private ChatSessionEntity toSessionEntity(ChatSessionPO po) {
        return ChatSessionEntity.builder()
                .id(po.getId())
                .agentId(po.getAgentId())
                .userId(po.getUserId())
                .title(po.getTitle())
                .messageCount(po.getMessageCount())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}

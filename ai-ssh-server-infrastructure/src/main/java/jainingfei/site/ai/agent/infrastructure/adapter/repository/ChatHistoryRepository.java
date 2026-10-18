package jainingfei.site.ai.agent.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatSessionEntity;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import jainingfei.site.ai.agent.infrastructure.dao.IChatMessageDao;
import jainingfei.site.ai.agent.infrastructure.dao.IChatMilestoneDao;
import jainingfei.site.ai.agent.infrastructure.dao.IChatSessionDao;
import jainingfei.site.ai.agent.infrastructure.dao.IChatTaskStateDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMessagePO;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatMilestonePO;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatSessionPO;
import jainingfei.site.ai.agent.infrastructure.dao.po.ChatTaskStatePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Resource
    private IChatTaskStateDao chatTaskStateDao;

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
        // updated_at 显式写 Java 时间：MySQL 靠 ON UPDATE CURRENT_TIMESTAMP，本地 H2 库没有该语义
        chatSessionDao.update(null, new LambdaUpdateWrapper<ChatSessionPO>()
                .eq(ChatSessionPO::getId, message.getSessionId())
                .set(ChatSessionPO::getUpdatedAt, LocalDateTime.now())
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

    @Override
    public void upsertTaskState(String sessionId, TaskStateVO taskState) {
        if (sessionId == null || taskState == null) {
            return;
        }
        ChatTaskStatePO po = toTaskStatePO(sessionId, taskState);
        if (chatTaskStateDao.selectById(sessionId) == null) {
            chatTaskStateDao.insert(po);
        } else {
            chatTaskStateDao.updateById(po);
        }
    }

    @Override
    public TaskStateVO loadTaskState(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        ChatTaskStatePO po = chatTaskStateDao.selectById(sessionId);
        return po == null ? null : toTaskStateVO(po);
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

    /** TaskStateVO → PO：steps 用 \u001F 行分隔（避免与命令/文本内容冲突） */
    private ChatTaskStatePO toTaskStatePO(String sessionId, TaskStateVO vo) {
        ChatTaskStatePO po = new ChatTaskStatePO();
        po.setSessionId(sessionId);
        po.setRootIntent(vo.getRootIntent() != null ? vo.getRootIntent().name() : null);
        po.setTaskDescription(vo.getTaskDescription());
        po.setSteps(vo.getSteps() == null || vo.getSteps().isEmpty()
                ? null : String.join("\u001F", vo.getSteps()));
        po.setCurrentStepIndex(vo.getCurrentStepIndex());
        po.setCompleted(vo.isCompleted() ? 1 : 0);
        po.setLastFeedbackFailed(vo.isLastFeedbackFailed() ? 1 : 0);
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** PO → TaskStateVO（脏数据容错：枚举名非法时 rootIntent 为 null） */
    private TaskStateVO toTaskStateVO(ChatTaskStatePO po) {
        IntentTypeEnumVO rootIntent = null;
        if (po.getRootIntent() != null && !po.getRootIntent().isBlank()) {
            try {
                rootIntent = IntentTypeEnumVO.valueOf(po.getRootIntent());
            } catch (IllegalArgumentException e) {
                // 脏数据容错：枚举演进后旧名失效 → 根意图为空，由下次业务分类重建
            }
        }
        List<String> steps = new ArrayList<>();
        if (po.getSteps() != null && !po.getSteps().isBlank()) {
            for (String step : po.getSteps().split("\u001F")) {
                if (!step.isBlank()) {
                    steps.add(step);
                }
            }
        }
        return TaskStateVO.builder()
                .taskDescription(po.getTaskDescription())
                .rootIntent(rootIntent)
                .steps(steps)
                .currentStepIndex(po.getCurrentStepIndex() != null ? po.getCurrentStepIndex() : 0)
                .completed(po.getCompleted() != null && po.getCompleted() == 1)
                .lastFeedbackFailed(po.getLastFeedbackFailed() != null && po.getLastFeedbackFailed() == 1)
                .build();
    }
}

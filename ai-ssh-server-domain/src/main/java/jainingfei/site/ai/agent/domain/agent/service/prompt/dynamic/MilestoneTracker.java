package jainingfei.site.ai.agent.domain.agent.service.prompt.dynamic;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.valobj.prompt.MilestoneVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 里程碑追踪器——基于正则规则自动识别用户纠偏、任务完成、任务切换、工具报错等关键事件，
 * 并按会话维度缓存。
 *
 * <p>设计要点：
 * <ul>
 *   <li>确定性方案——正则匹配，不调用模型做事件识别，毫秒级零成本</li>
 *   <li>会话隔离——ConcurrentHashMap key=sessionId，多用户互不干扰</li>
 *   <li>容量上限——每会话最多 50 条，超出淘汰最老的，防止长对话撑爆内存</li>
 *   <li>按角色区分——同一词在不同角色含义不同（用户说"错了"是纠偏，工具输出 error 是执行失败）</li>
 *   <li>持久化（2-8）——写入时旁路落库 chat_milestone；读取时 DB 优先、内存兜底，
 *       重启后里程碑不丢失</li>
 * </ul>
 */
@Slf4j
@Component
public class MilestoneTracker {

    private static final int MAX_MILESTONES = 50;

    private final Map<String, LinkedList<MilestoneVO>> milestones = new ConcurrentHashMap<>();

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    /**
     * 检测并记录里程碑事件。
     *
     * <p>按角色分别识别：
     * <ul>
     *   <li>user —— "不对/换个思路"记为 TASK_CHANGE，"完成/搞定"记为 TASK_COMPLETE，"不要/停/别"记为 USER_CORRECTION</li>
     *   <li>tool —— error/failed/exception/permission denied 等关键词记为 ERROR</li>
     * </ul>
     *
     * @param sessionId 会话 ID，按会话隔离里程碑
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容，为 null 或空时直接返回
     */
    public void detectAndRecord(String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isEmpty()) return;

        MilestoneVO.Type type = null;

        if ("user".equals(role)) {
            if (matches(content, "不对|不是这样|改一下|换个思路|换种方式|错了")) {
                type = MilestoneVO.Type.TASK_CHANGE;
            } else if (matches(content, "完成了|搞定|结束|好了")) {
                type = MilestoneVO.Type.TASK_COMPLETE;
            } else if (matches(content, "不要|停|别")) {
                type = MilestoneVO.Type.USER_CORRECTION;
            }
        }

        if ("tool".equals(role)) {
            if (matches(content, "(?i)error|failed|exception|permission denied|not found|refused")) {
                type = MilestoneVO.Type.ERROR;
            }
        }

        if (type != null) {
            push(sessionId, MilestoneVO.builder()
                    .type(type)
                    .content(truncate(content, 200))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("里程碑记录: sessionId={}, type={}, content={}", sessionId, type, truncate(content, 100));
        }
    }

    /**
     * 获取指定会话最近的 N 条里程碑事件。
     *
     * <p>DB 优先：重启后内存为空，从 chat_milestone 表恢复，保证持久化不丢失；
     * DB 查询失败或无数据时降级到内存缓存。
     *
     * @param sessionId 会话 ID
     * @param limit     返回条数上限
     * @return 里程碑列表（按时间正序），无数据时返回空列表
     */
    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        // 1. DB 优先查询（2-8 新增）
        try {
            List<MilestoneVO> recentMilestones = chatHistoryRepository.getRecentMilestones(sessionId, limit);
            if (recentMilestones != null && !recentMilestones.isEmpty()) {
                return recentMilestones;
            }
        } catch (Exception e) {
            log.warn("从 DB 查询里程碑失败，降级内存缓存 sessionId={}", sessionId, e);
        }

        // 2. 内存缓存兜底
        LinkedList<MilestoneVO> list = milestones.getOrDefault(sessionId, new LinkedList<>());
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    /**
     * 清除指定会话的全部里程碑记录。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        milestones.remove(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    /** 追加里程碑到会话队列，超出上限淘汰最老记录；同时旁路落库，DB 失败不影响主流程 */
    private void push(String sessionId, MilestoneVO milestoneVO) {
        LinkedList<MilestoneVO> list = milestones.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) {
                list.removeFirst();
            }
        }

        // 数据库持久化（2-8 新增）：旁路写入，记忆持久化是"锦上添花"而非"生死攸关"
        try {
            chatHistoryRepository.saveMilestone(sessionId, milestoneVO);
        } catch (Exception e) {
            log.error("保存里程碑失败 sessionId={}", sessionId, e);
        }
    }

    /** 全文正则匹配 */
    private boolean matches(String content, String regex) {
        return Pattern.compile(".*(" + regex + ").*").matcher(content).matches();
    }

    /** 截断字符串到指定长度 */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}

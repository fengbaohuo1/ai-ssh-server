package jainingfei.site.ai.agent.test.domain.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.ILongTermMemoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.LongTermMemoryEntity;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import jainingfei.site.ai.agent.infrastructure.dao.ILongTermMemoryDao;
import jainingfei.site.ai.agent.infrastructure.dao.po.LongTermMemoryPO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆服务集成测试（2-8）
 * <p>
 * 覆盖四类记忆提取（偏好/环境/版本/排查案例）、UNIQUE 键去重（hit_count 递增）、
 * 粗筛+精排召回闭环。仅依赖 MySQL（dev 库）。测试数据用专用 userId 并在收尾清理。
 */
@Slf4j
@SpringBootTest
public class LongTermMemoryServiceTest {

    private static final String USER_ID = "ltm-test-user";
    private static final String SESSION_ID = "ltm-test-session";

    @Resource
    private ILongTermMemoryService longTermMemoryService;
    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;
    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    @Test
    public void test_extract_and_recall_01() {
        log.info("========== 长期记忆服务测试开始 ==========");
        cleanup();

        // 1. 用户偏好提取：含偏好词"以后"→ USER_PREFERENCE
        longTermMemoryService.saveUserMessage(USER_ID, SESSION_ID,
                "以后操作生产服务器都先加 sudo 再执行命令", "TASK", true);
        List<LongTermMemoryEntity> memories = longTermMemoryRepository.queryRecentByUserId(USER_ID, 10);
        assertTrue(memories.stream().anyMatch(m -> "USER_PREFERENCE".equals(m.getMemoryType())),
                "应提取出用户偏好记忆");
        log.info("偏好提取 OK: {}", memories.stream()
                .filter(m -> "USER_PREFERENCE".equals(m.getMemoryType()))
                .map(LongTermMemoryEntity::getContent).findFirst().orElse(""));

        // 2. 同一偏好重复表达 → 去重（UNIQUE 键），hit_count 递增
        longTermMemoryService.recordUserMessage(USER_ID, SESSION_ID,
                "以后操作生产服务器都先加 sudo 再执行命令", "TASK");
        LongTermMemoryEntity pref = queryByType("USER_PREFERENCE");
        assertEquals(2, pref.getHitCount(), "重复偏好应走更新，hit_count=2");
        log.info("偏好去重 OK: hit_count={}", pref.getHitCount());

        // 3. 工具输出提取：redis 版本（专用正则）+ 环境事实（OS）
        longTermMemoryService.saveToolMessage(USER_ID, SESSION_ID, "executeCommand", "call_1",
                "redis_version:7.0.11\nprocess_id:1\nrun_id:abc123", true);
        longTermMemoryService.saveToolMessage(USER_ID, SESSION_ID, "executeCommand", "call_2",
                "Linux 5.15.0-91-generic x86_64", true);
        assertTrue(queryByType("SOFTWARE_FACT") != null, "应提取出 redis 版本记忆");
        assertTrue(queryByType("ENVIRONMENT_FACT") != null, "应提取出 OS 环境事实");
        log.info("版本/环境提取 OK: redis={}",
                queryByType("SOFTWARE_FACT").getContent());

        // 4. 排查案例：助手结论（含"结论/根因"）
        longTermMemoryService.saveAssistantMessage(USER_ID, SESSION_ID,
                "结论：502 的根因是上游服务端口未监听，修复方式是重启 upstream 服务。");
        assertTrue(queryByType("TROUBLESHOOTING_CASE") != null, "应提取出排查案例记忆");

        // 5. 召回闭环：查询带 redis 关键词 → 版本记忆应命中且排序靠前
        List<LongTermMemoryEntity> recalled = longTermMemoryService.queryRelevantMemories(
                USER_ID, "redis 版本是多少", 5);
        assertFalse(recalled.isEmpty(), "redis 查询应召回相关记忆");
        assertEquals("SOFTWARE_FACT", recalled.get(0).getMemoryType(),
                "redis 版本记忆应排第一");
        log.info("召回闭环 OK: top1=[{}] {}", recalled.get(0).getMemoryType(), recalled.get(0).getContent());

        // 6. 摘要构建
        String summary = longTermMemoryService.buildMemorySummary(USER_ID, "redis 版本", 5);
        assertFalse(summary.isBlank(), "摘要不应为空");
        assertTrue(summary.contains("SOFTWARE_FACT"));
        log.info("记忆摘要 OK:\n{}", summary);

        // 7. 失败内容启发式
        assertTrue(longTermMemoryService.isFailureContent("cat: /etc/xxx: Permission denied"));
        assertFalse(longTermMemoryService.isFailureContent("total 40K drwxr-xr-x 2 root root"));

        log.info("========== 长期记忆服务测试通过 ==========");
    }

    private LongTermMemoryEntity queryByType(String memoryType) {
        return longTermMemoryRepository.queryRecentByUserId(USER_ID, 50).stream()
                .filter(m -> memoryType.equals(m.getMemoryType()))
                .findFirst().orElse(null);
    }

    private void cleanup() {
        longTermMemoryDao.delete(new LambdaQueryWrapper<LongTermMemoryPO>()
                .eq(LongTermMemoryPO::getUserId, USER_ID));
    }
}

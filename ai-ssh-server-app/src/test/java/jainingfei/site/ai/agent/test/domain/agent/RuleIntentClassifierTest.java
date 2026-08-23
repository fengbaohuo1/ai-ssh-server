package jainingfei.site.ai.agent.test.domain.agent;

import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.ConversationContextVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentHistoryEntryVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentResultVO;
import jainingfei.site.ai.agent.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import jainingfei.site.ai.agent.domain.agent.service.intent.classifier.impl.RuleIntentClassifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则意图分类器单元测试（无网络，纯打分公式验证）
 * <p>
 * 规则层打分公式：
 * <pre>
 *   关键词命中   → 0.5 + (hits-1)*0.1，上限 0.7
 *   正则命中     → +0.2
 *   上下文加权   → +0.1（最近意图历史命中同一意图）
 *   置信度 ≥ 0.8 时 IntentService 直接采信，跳过 LLM 调用
 * </pre>
 * LLM 层需要真实模型 API，见 {@link IntentServiceTest}（@Disabled 集成测试）。
 */
public class RuleIntentClassifierTest {

    private final RuleIntentClassifier classifier = new RuleIntentClassifier();

    @Test
    public void test_diagnose_high_confidence() {
        // "502"+"挂了" 双关键词命中 0.6 + 正则 "为什么.*挂" 命中 +0.2 = 0.8 → 直接采信
        IntentResultVO result = classifier.classify("nginx 502了帮我看看为什么挂了", null);
        assertEquals(IntentTypeEnumVO.DIAGNOSE, result.getIntent());
        assertTrue(result.getConfidence() >= 0.8, "期望置信度 ≥0.8，实际 " + result.getConfidence());
        assertEquals("nginx", result.getEntities().get("service"));
    }

    @Test
    public void test_monitor_keyword_only() {
        // 仅关键词命中（无正则）："磁盘" 1 个关键词 → 0.5，低于 0.8 需下沉 LLM 层
        IntentResultVO result = classifier.classify("查一下磁盘剩余空间", null);
        assertEquals(IntentTypeEnumVO.MONITOR, result.getIntent());
        assertTrue(result.getConfidence() >= 0.5 && result.getConfidence() < 0.8,
                "期望置信度 [0.5, 0.8)，实际 " + result.getConfidence());
    }

    @Test
    public void test_configure_pattern() {
        // 正则 "修改.*conf" 命中 +0.2 + 关键词 "配置" 0.5 = 0.7
        IntentResultVO result = classifier.classify("帮我修改一下 nginx 的 conf 配置", null);
        assertEquals(IntentTypeEnumVO.CONFIGURE, result.getIntent());
        assertTrue(result.getConfidence() >= 0.6);
    }

    @Test
    public void test_compound_two_intents() {
        // "查看"（MONITOR）+ "配置"（CONFIGURE）双意图 ≥0.5 → COMPOUND
        IntentResultVO result = classifier.classify("看下 502 是不是因为改了配置导致的", null);
        assertEquals(IntentTypeEnumVO.COMPOUND, result.getIntent());
        assertTrue(result.getCandidateIntents().size() >= 2);
    }

    @Test
    public void test_continue_without_task_state_low_confidence() {
        // 纯"继续"指令但无任务态 → CONTINUE conf=0.4（由 LLM/主模型兜底）
        IntentResultVO result = classifier.classify("继续", null);
        assertEquals(IntentTypeEnumVO.CONTINUE, result.getIntent());
        assertEquals(0.4, result.getConfidence(), 0.001);
    }

    @Test
    public void test_continue_with_task_state_high_confidence() {
        // 有进行中任务态 → CONTINUE conf=0.95
        ConversationContextVO context = ConversationContextVO.builder()
                .recentIntents(new LinkedList<>())
                .build();
        context.setTaskState(jainingfei.site.ai.agent.domain.agent.model.valobj.intent.TaskStateVO.builder()
                .rootIntent(IntentTypeEnumVO.DIAGNOSE)
                .currentStepIndex(1)
                .completed(false)
                .build());
        IntentResultVO result = classifier.classify("继续", context);
        assertEquals(IntentTypeEnumVO.CONTINUE, result.getIntent());
        assertEquals(0.95, result.getConfidence(), 0.001);
    }

    @Test
    public void test_continue_deploy_not_misclassified() {
        // "继续部署" 不是纯继续指令 → 应走业务意图 DEPLOY
        IntentResultVO result = classifier.classify("继续部署新版本", null);
        assertEquals(IntentTypeEnumVO.DEPLOY, result.getIntent());
    }

    @Test
    public void test_unknown_no_hit() {
        IntentResultVO result = classifier.classify("今天天气怎么样", null);
        assertEquals(IntentTypeEnumVO.UNKNOWN, result.getIntent());
        assertEquals(0.0, result.getConfidence(), 0.001);
    }

    @Test
    public void test_context_weighting() {
        // 上下文加权：最近意图含 SEARCH → "找一下那个文件" 命中 SEARCH 关键词时 +0.1
        ConversationContextVO context = ConversationContextVO.builder()
                .recentIntents(new LinkedList<>())
                .build();
        context.getRecentIntents().add(IntentHistoryEntryVO.builder()
                .intent(IntentTypeEnumVO.SEARCH).confidence(0.9).timestamp(System.currentTimeMillis())
                .build());
        IntentResultVO withContext = classifier.classify("帮我找一下那个文件", context);
        IntentResultVO withoutContext = classifier.classify("帮我找一下那个文件", null);
        assertEquals(IntentTypeEnumVO.SEARCH, withContext.getIntent());
        assertTrue(withContext.getConfidence() > withoutContext.getConfidence(),
                "上下文加权应提升置信度");
    }
}

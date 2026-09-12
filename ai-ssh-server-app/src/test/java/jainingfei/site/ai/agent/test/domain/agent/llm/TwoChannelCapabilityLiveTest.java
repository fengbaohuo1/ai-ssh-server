package jainingfei.site.ai.agent.test.domain.agent.llm;

import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.ReasoningDraftProtocol;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiResponseAggregator;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.UsageReporter;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ChatModelFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderCapabilities;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import jainingfei.site.ai.agent.domain.agent.support.ReasoningTagUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 双通道能力对拍（真实模型，默认不跑）——把 {@link ProviderCapabilities} 里声明的能力
 * 用"一次真实流式调用"验一遍，防止能力表变成一厢情愿的注释。
 *
 * <p>为什么需要它：能力缺口不会报错，只会<b>静静地少</b>——协议通道丢的是流式思考草稿
 * （上游 chunk 合并缺口），原生通道多的是草稿与原生缓存明细。这类差异只有真跑一次才看得见，
 * 而单元测试用的都是假模型。
 *
 * <p>对拍结论（2026-09 实测，deepseek-flash）：
 * <pre>
 *                              deepseek-native     openai-compatible
 *   流式分片带草稿                  ✔（专有消息类型）      ✔（元数据键 reasoningContent，259/260 片）
 *   聚合事件带隐藏草稿标记            ✔                  ✔
 *   流式 usage/缓存命中             ✔（原生 usage 明细）   ✔（通用字段）
 *   显式关思考                     ✔                  ✘（协议无此参数，但 temperature 不受限）
 * </pre>
 * 也就是说：两条通道在"草稿 + 缓存"上<b>能力对等、出口不同</b>——差异被两个 SPI 吸收，
 * 上层只读能力位。跨厂商时换成协议通道即可，本项目保留原生通道是为了 DeepSeek 自己的
 * 显式思考开关与原生 usage 明细。
 *
 * <p>运行方式（需要联网与真实 API Key，所以默认不参与构建）：
 * <pre>
 *   $env:AI_SSH_LIVE_LLM="true"; $env:AI_SSH_API_KEY="sk-..."
 *   mvn -o test "-Dtest=TwoChannelCapabilityLiveTest" "-Dsurefire.failIfNoSpecifiedTests=false"
 * </pre>
 * 可选环境变量：{@code AI_SSH_BASE_URL}（默认 https://api.deepseek.com）、{@code AI_SSH_MODEL}（默认 deepseek-flash）。
 * 每轮会打印一行诊断（分片数 / 带草稿的分片数 / 草稿字符数 / 聚合响应数 / 缓存命中 / 是否带标记），
 * 用来定位"草稿是在 provider 层丢的，还是在聚合器里丢的"。
 */
@EnabledIfEnvironmentVariable(named = "AI_SSH_LIVE_LLM", matches = "true",
        disabledReason = "真实模型集成测试：需显式打开 AI_SSH_LIVE_LLM 并提供 API Key")
public class TwoChannelCapabilityLiveTest {

    private static final String BASE_URL = System.getenv().getOrDefault("AI_SSH_BASE_URL", "https://api.deepseek.com");
    private static final String API_KEY = System.getenv("AI_SSH_API_KEY");
    private static final String MODEL = System.getenv().getOrDefault("AI_SSH_MODEL", "deepseek-flash");

    /** 让人话思考的提问：保证模型（若支持）会先产出推理内容 */
    private static final String QUESTION = "先想一下再回答：用一句话说明为什么 1+1=2。";

    @BeforeEach
    public void requireApiKey() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(), "缺少环境变量 AI_SSH_API_KEY，跳过真实模型对拍");
    }

    @Test
    @DisplayName("deepseek-native：流式响应携带思考草稿（专有消息类型）→ 聚合事件带隐藏草稿标记")
    public void test_native_channel_carries_streaming_draft() {
        Called called = call(ProviderCapabilities.DEEPSEEK_NATIVE);

        assertTrue(called.profile.capabilities().isReasoningDraftStreaming(), "能力声明应为支持流式草稿");
        assertTrue(called.aggregatedText.startsWith(ReasoningTagUtil.OPEN),
                "原生通道的聚合事件应以草稿标记开头（DeepSeekAssistantMessage.reasoningContent 被读到）");
        String[] parsed = ReasoningTagUtil.parse(called.aggregatedText);
        assertNotNull(parsed[0], "草稿内容可解析");
        assertFalse(parsed[0].isBlank(), "草稿内容非空");
        assertTrue(parsed[1] != null && !parsed[1].isBlank(), "可见正文非空");
        assertTrue(called.draftChunks > 0, "应能观察到带草稿的分片（走通道自己的读法）");
        assertNotNull(called.cachedTokens, "流式 usage 应带缓存命中字段（本轮可能为 0：无重复前缀）");
    }

    @Test
    @DisplayName("openai-compatible：同一份代码同样拿到流式草稿（元数据键出口）+ 缓存命中")
    public void test_protocol_channel_also_carries_streaming_draft() {
        Called called = call(ProviderCapabilities.OPENAI_COMPATIBLE);

        assertTrue(called.profile.capabilities().isReasoningDraftStreaming(),
                "协议通道同样能拿到流式草稿——此前据 mergeDeltas 源码误判为缺口，实测纠正");
        assertTrue(called.aggregatedText.startsWith(ReasoningTagUtil.OPEN),
                "协议通道的聚合事件同样以草稿标记开头（元数据键 reasoningContent）");
        assertTrue(called.draftChunks > 0, "应能观察到带草稿的分片");
        assertTrue(called.aggregatedText.length() > ReasoningTagUtil.OPEN.length(), "可见正文仍在标记之后");
        assertNotNull(called.cachedTokens, "协议通道的缓存命中走通用 usage 字段");
    }

    // ===== 一次真实流式调用（走与生产完全相同的档案 + 聚合器） =====

    private static Called call(String providerId) {
        AiAgentConfigTableVO.Module.ChatModel config = new AiAgentConfigTableVO.Module.ChatModel();
        config.setProvider(providerId);
        config.setBaseUrl(BASE_URL);
        config.setApiKey(API_KEY);
        config.setModel(MODEL);

        ProviderProfile profile = new ChatModelFactory().createMain(config);
        SpringAiResponseAggregator aggregator = new SpringAiResponseAggregator(
                new ReasoningDraftProtocol(), new UsageReporter(),
                profile.reasoningDraft(), profile.cacheMetrics());

        // options 必须带模型自己的子类（provider 内部强转，详见 SpringAiPromptAssembler 的注释）
        Prompt prompt = new Prompt(List.of(new UserMessage(QUESTION)), profile.chatModel().getOptions());

        // 旁路记录原始分片：用于诊断"草稿是在哪一层丢的"（provider 分片 → 聚合器）
        List<org.springframework.ai.chat.model.ChatResponse> rawChunks = new java.util.ArrayList<>();
        Flowable<org.springframework.ai.chat.model.ChatResponse> source =
                Flowable.fromPublisher(((StreamingChatModel) profile.chatModel()).stream(prompt))
                        .doOnNext(rawChunks::add);

        List<com.google.adk.models.LlmResponse> responses = aggregator
                .toLlmResponses(source)
                .toList()
                .blockingGet();

        // 聚合事件 = 唯一不带 partial 标记的那条（分片事件全是 partial=true）
        com.google.adk.models.LlmResponse aggregated = responses.stream()
                .filter(response -> response.partial().isEmpty() || !response.partial().get())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("没有聚合事件：" + responses.size() + " 条响应"));

        Integer cached = aggregated.usageMetadata()
                .map(metadata -> metadata.cachedContentTokenCount().orElse(null))
                .orElse(null);

        String aggregatedText = aggregated.content().map(Content::text).orElse("");
        aggregatedText = aggregatedText == null ? "" : aggregatedText;

        // 诊断：按"我们自己的提取逻辑"统计带草稿的分片——通道读法由档案提供（原生通道走专有消息类型，
        // 协议通道走元数据键），所以这里不能用元数据键硬编码，否则会误判成"原生通道没有草稿"。
        ReasoningDraftProtocol protocol = new ReasoningDraftProtocol();
        int draftChunks = 0;
        int draftChars = 0;
        for (org.springframework.ai.chat.model.ChatResponse chunk : rawChunks) {
            String fragment = protocol.extractDraftFragment(chunk, profile.reasoningDraft());
            if (fragment != null && !fragment.isEmpty()) {
                draftChunks++;
                draftChars += fragment.length();
            }
        }
        System.out.println("LIVE|provider=" + providerId
                + "|chunks=" + rawChunks.size()
                + "|draftChunks=" + draftChunks
                + "|draftChars=" + draftChars
                + "|llmResponses=" + responses.size()
                + "|cachedTokens=" + cached
                + "|marker=" + aggregatedText.contains(ReasoningTagUtil.OPEN));

        return new Called(profile, aggregatedText, cached, responses.size(), draftChunks);
    }

    /** 一次调用的结果快照 */
    private record Called(ProviderProfile profile, String aggregatedText, Integer cachedTokens,
                          int responseCount, int draftChunks) {
    }
}

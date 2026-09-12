package jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ADK 与 Spring AI 之间的适配器——Spring AI 负责通信，ADK 负责工具循环与编排（框架托管循环）。
 *
 * <p>本类只做"接线"，具体职责各自成类：
 * <pre>
 *   请求侧  SpringAiPromptAssembler    LlmRequest → Prompt（消息组装、工具声明、历史配对规则）
 *   响应侧  SpringAiResponseAggregator ChatResponse → LlmResponse（分片 + 一条聚合事件）
 *   草稿    ReasoningDraftProtocol     推理内容的落库标记与历史回放约定
 *   usage   UsageReporter              token/缓存命中观测与 usageMetadata 映射
 *   排障    LlmErrorDiagnostics        失败时的服务端响应体与请求形状
 * </pre>
 *
 * <p>能力的通道差异（推理内容读法、缓存命中读法）通过 {@link ReasoningDraftAccess} /
 * {@link CacheMetricsAccess} 注入；未注入时走 Spring AI 的通用约定（元数据键 / 通用 usage 字段）。
 *
 * <p>线程模型：无状态，可被多个智能体（父/子）共享；每次调用的中间状态都封装在聚合器的方法栈里。
 */
@Slf4j
public class SpringAiLlmAdapter extends BaseLlm {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ReasoningDraftProtocol draftProtocol;
    private final SpringAiPromptAssembler promptAssembler;
    private final SpringAiResponseAggregator responseAggregator;
    private final LlmErrorDiagnostics diagnostics = new LlmErrorDiagnostics();

    public SpringAiLlmAdapter(ChatModel chatModel) {
        this(chatModel, null, null);
    }

    /**
     * @param chatModel     Spring AI 模型（通信层）
     * @param draftAccess   通道的推理内容读法（null = 走元数据键默认约定）
     * @param cacheMetrics  通道的缓存命中读法（null = 只读通用 usage 字段）
     */
    public SpringAiLlmAdapter(ChatModel chatModel,
                              ReasoningDraftAccess draftAccess,
                              CacheMetricsAccess cacheMetrics) {
        super(extractModelName(chatModel));
        this.chatModel = chatModel;
        this.streamingChatModel = chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null;
        this.draftProtocol = new ReasoningDraftProtocol();
        this.promptAssembler = new SpringAiPromptAssembler(chatModel, draftProtocol);
        this.responseAggregator = new SpringAiResponseAggregator(
                draftProtocol, new UsageReporter(), draftAccess, cacheMetrics);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        try {
            Prompt prompt = promptAssembler.toPrompt(request);

            if (stream && streamingChatModel != null) {
                Flowable<ChatResponse> chunks = Flowable.fromPublisher(streamingChatModel.stream(prompt))
                        .doOnError(error -> diagnostics.logFailure(error, prompt, draftProtocol));
                return responseAggregator.toLlmResponses(chunks);
            }

            ChatResponse response;
            try {
                response = chatModel.call(prompt);
            } catch (Exception e) {
                diagnostics.logFailure(e, prompt, draftProtocol);
                throw e;
            }
            LlmResponse aggregated = responseAggregator.toAggregatedResponse(response);
            return aggregated == null ? Flowable.empty() : Flowable.just(aggregated);
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Live connection not supported");
    }

    /** 模型名（ADK 用于 LlmRequest.model，取通信层实现类名做标识） */
    private static String extractModelName(Object model) {
        return model.getClass().getSimpleName().toLowerCase()
                .replace("chatmodel", "").replace("model", "");
    }
}

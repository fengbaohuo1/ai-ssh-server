package jainingfei.site.ai.agent.test.domain.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身：可编程行为的子智能体（不接模型、不联网）。
 *
 * <p>用来在单元测试里驱动"派发 → 独立会话 → 结果收敛"这条链路，
 * 并记录每次运行看到的会话信息（会话 ID、初始 state），供断言终端会话透传与会话隔离。
 */
public class StubSubAgent extends BaseAgent {

    /** 行为：给定调用上下文，返回事件流 */
    public interface Behavior {
        Flowable<Event> run(InvocationContext ctx);
    }

    private final Behavior behavior;
    private final AtomicInteger runs = new AtomicInteger();
    private final List<String> seenSessionIds = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> seenStates = new ConcurrentHashMap<>();

    public StubSubAgent(String name, Behavior behavior) {
        super(name, name + " 描述", List.of(), List.of(), List.of());
        this.behavior = behavior;
    }

    /** 每次都回复固定文本 */
    public static StubSubAgent replying(String name, String text) {
        return new StubSubAgent(name, ctx -> Flowable.just(textEvent(ctx, name, text)));
    }

    /** 每次都抛错（模拟执行期失败） */
    public static StubSubAgent failing(String name, String error) {
        return new StubSubAgent(name, ctx -> Flowable.error(new IllegalStateException(error)));
    }

    /** 前 failures 次抛错，之后回复文本（模拟"重试后恢复"） */
    public static StubSubAgent failTimesThenReply(String name, int failures, String text) {
        AtomicInteger count = new AtomicInteger();
        return new StubSubAgent(name, ctx -> count.incrementAndGet() <= failures
                ? Flowable.error(new IllegalStateException("第 " + count.get() + " 次执行失败"))
                : Flowable.just(textEvent(ctx, name, text)));
    }

    /** 无任何事件（模拟"成功但结果为空"） */
    public static StubSubAgent silent(String name) {
        return new StubSubAgent(name, ctx -> Flowable.empty());
    }

    private static Event textEvent(InvocationContext ctx, String author, String text) {
        return Event.builder()
                .invocationId(ctx.invocationId())
                .author(author)
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .build();
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        runs.incrementAndGet();
        String sessionId = invocationContext.session().id();
        seenSessionIds.add(sessionId);
        seenStates.put(sessionId, new LinkedHashMap<>(invocationContext.session().state()));
        return behavior.run(invocationContext);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return Flowable.empty();
    }

    /** 实际运行次数 */
    public int runs() {
        return runs.get();
    }

    /** 最后一次运行看到的会话 ID */
    public String lastSessionId() {
        return seenSessionIds.isEmpty() ? null : seenSessionIds.get(seenSessionIds.size() - 1);
    }

    /** 某次运行看到的会话初始 state 快照 */
    public Map<String, Object> stateOf(String sessionId) {
        return seenStates.get(sessionId);
    }

    /** 所有运行看到的会话 ID */
    public List<String> sessionIds() {
        return List.copyOf(seenSessionIds);
    }
}

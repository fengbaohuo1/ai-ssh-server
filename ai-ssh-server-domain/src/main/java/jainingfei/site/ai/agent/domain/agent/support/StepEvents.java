package jainingfei.site.ai.agent.domain.agent.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 步骤事件协议 v2——<b>事件形状的唯一出处</b>（domain 侧只造 Map，序列化与发射在 case 层）。
 *
 * <p>信封沿用旧协议：{@code {"v":"<文本增量>","s":<step 或 step[]>}}；本类只负责构造 step。
 *
 * <p>为什么要 v2：旧 step 只有 {@code t/n/a/r}，于是
 * <pre>
 *   ① 没有 id → 前端只能按 FIFO 配对 call/result，并发（批量派发走线程池、子智能体一次发多条命令）必然串台
 *   ② 没有父子关系 → 子智能体做的事（命令与输出）根本没地方挂，界面上只剩一个空壳卡片
 *   ③ 没有状态与耗时 → "在跑 / 成功 / 失败 / 重试第几次 / 花了多久"全靠猜
 * </pre>
 *
 * <p>新增字段（全部可选，缺失即旧行为）：
 * <table border="1">
 *   <caption>字段</caption>
 *   <tr><td>{@code id}</td><td>步骤稳定标识（ADK call id / FR id / taskId），前端按 id 幂等 upsert</td></tr>
 *   <tr><td>{@code p}</td><td>父步骤 id（嵌套键）</td></tr>
 *   <tr><td>{@code k}</td><td>种类：tool / agent / plan（决定卡片形态）</td></tr>
 *   <tr><td>{@code st}</td><td>生命周期：running / ok / error</td></tr>
 *   <tr><td>{@code ms}</td><td>耗时（毫秒，终态步骤才带）</td></tr>
 *   <tr><td>{@code att}/{@code maxAtt}</td><td>重试第几次 / 上限（派发任务）</td></tr>
 *   <tr><td>{@code ag}</td><td>该步骤所属子智能体名（子 Runner 内的事件）</td></tr>
 *   <tr><td>{@code m}</td><td>人类可读消息（错误原因等）</td></tr>
 * </table>
 *
 * <p>放在 domain 而不是 case：子智能体执行器（{@link jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SubAgentDispatchService}）
 * 要上报嵌套步骤，而 case 层依赖 domain，反向不行——协议形状因此下沉到 domain，只留"怎么发"在 case 层。
 */
public final class StepEvents {

    // ===== t：事件类型 =====
    public static final String T_THINKING = "thinking";
    public static final String T_TOOL_CALL = "tool_call";
    public static final String T_TOOL_RESULT = "tool_result";
    public static final String T_USAGE = "usage";
    public static final String T_DONE = "done";
    public static final String T_ERROR = "error";

    // ===== st：生命周期 =====
    public static final String ST_RUNNING = "running";
    public static final String ST_OK = "ok";
    public static final String ST_ERROR = "error";

    // ===== k：步骤种类 =====
    public static final String K_TOOL = "tool";
    public static final String K_AGENT = "agent";
    public static final String K_PLAN = "plan";

    // ===== 字段名（避免各处拼字符串） =====
    public static final String F_TYPE = "t";
    public static final String F_ID = "id";
    public static final String F_PARENT = "p";
    public static final String F_KIND = "k";
    public static final String F_NAME = "n";
    public static final String F_ARGS = "a";
    public static final String F_RESULT = "r";
    public static final String F_STATUS = "st";
    public static final String F_ELAPSED_MS = "ms";
    public static final String F_ATTEMPT = "att";
    public static final String F_MAX_ATTEMPT = "maxAtt";
    public static final String F_AGENT = "ag";
    public static final String F_MESSAGE = "m";
    public static final String F_STOP_REASON = "sr";
    public static final String F_PROMPT_TOKENS = "pt";
    public static final String F_COMPLETION_TOKENS = "ct";
    public static final String F_TOTAL_TOKENS = "tt";
    public static final String F_CACHED_TOKENS = "cached";

    private StepEvents() {
    }

    /** 初始 thinking 事件（回合已受理、尚未产出） */
    public static Map<String, Object> thinking() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_THINKING);
        return step;
    }

    /**
     * 工具调用步骤（运行中）。
     *
     * @param id       工具调用 id（ADK functionCall id；可为 null）
     * @param name     工具名 / 子智能体名
     * @param args     入参（对象；可为 null）
     * @param kind     种类（null 时按工具步骤处理）
     * @param parentId 父步骤 id（顶层为 null）
     */
    public static Map<String, Object> toolCall(String id, String name, Map<String, Object> args,
                                               String kind, String parentId) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_TOOL_CALL);
        step.put(F_STATUS, ST_RUNNING);
        put(step, F_ID, id);
        put(step, F_NAME, name);
        put(step, F_ARGS, args);
        put(step, F_KIND, kind);
        put(step, F_PARENT, parentId);
        return step;
    }

    /**
     * 工具结果步骤（终态）。
     *
     * @param result    结果对象（executeCommand = {command,output,success}；派发 = {success,result|error}）
     * @param success   是否成功（决定 st）
     * @param elapsedMs 耗时（毫秒；null 不带）
     */
    public static Map<String, Object> toolResult(String id, String name, Object result,
                                                 boolean success, Long elapsedMs) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_TOOL_RESULT);
        step.put(F_STATUS, success ? ST_OK : ST_ERROR);
        put(step, F_ID, id);
        put(step, F_NAME, name);
        put(step, F_RESULT, result);
        put(step, F_ELAPSED_MS, elapsedMs);
        return step;
    }

    /** 给步骤补字段（派发链路在已构造的步骤上追加 {@code p/ag/att} 用） */
    public static Map<String, Object> with(Map<String, Object> step, String key, Object value) {
        put(step, key, value);
        return step;
    }

    /** 本回合 token 累计（一次 LLM 调用一条 usageMetadata，回合内求和） */
    public static Map<String, Object> usage(long promptTokens, long completionTokens,
                                            long totalTokens, long cachedTokens) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_USAGE);
        step.put(F_PROMPT_TOKENS, promptTokens);
        step.put(F_COMPLETION_TOKENS, completionTokens);
        step.put(F_TOTAL_TOKENS, totalTokens);
        step.put(F_CACHED_TOKENS, cachedTokens);
        return step;
    }

    /** 结束事件（{@code sr} = stopReason：completed / error） */
    public static Map<String, Object> done(String stopReason) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_DONE);
        put(step, F_STOP_REASON, stopReason);
        return step;
    }

    /** 错误事件（前端必须能看到原因，而不是流突然结束） */
    public static Map<String, Object> error(String message) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put(F_TYPE, T_ERROR);
        put(step, F_MESSAGE, message);
        return step;
    }

    /** 只放非空值：空字段不下发，前端按缺省语义处理（兼容旧后端） */
    private static void put(Map<String, Object> step, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isEmpty()) {
            return;
        }
        step.put(key, value);
    }
}

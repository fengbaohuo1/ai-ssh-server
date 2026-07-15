package jainingfei.site.ai.agent.domain.agent.service.armory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Agent 动作步骤缓冲区（单例），用于在对话期间捕获 Agent 的动作步骤（思考、工具调用、工具返回）。
 * <p>
 * 生产端：OpenAiChatModelAdapter 工具循环写入；消费端：SSE Controller 线程读取。
 */
public class AgentStepBuffer {

    private static final AgentStepBuffer INSTANCE = new AgentStepBuffer();
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    public static AgentStepBuffer getInstance() {
        return INSTANCE;
    }

    public void append(String stepJson) {
        queue.add(stepJson);
    }

    public String drain() {
        StringBuilder sb = new StringBuilder();
        String chunk;
        boolean first = true;
        while ((chunk = queue.poll()) != null) {
            if (!first) sb.append(",");
            sb.append(chunk);
            first = false;
        }
        return sb.toString();
    }

    public String drainOne() {
        return queue.poll();
    }

    public void clear() {
        queue.clear();
    }
}

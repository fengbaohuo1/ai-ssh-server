package jainingfei.site.ai.agent.api.dto;

import lombok.Data;

/**
 * ReAct 对话事件 DTO — 结构化 SSE 事件协议。
 *
 * <p>支持的事件类型：
 * <ul>
 *   <li>text — 文本片段</li>
 *   <li>tool_call — 工具调用开始</li>
 *   <li>tool_result — 工具执行结果</li>
 *   <li>round_end — 一轮结束</li>
 *   <li>done — 全部完成</li>
 *   <li>error — 错误</li>
 * </ul>
 */
@Data
public class ReActEventDTO {

    /** 事件类型：text / tool_call / tool_result / round_end / done / error */
    private String event;

    /** 事件内容（文本片段或工具结果） */
    private String content;

    /** 完整累积文本（event=text 时） */
    private String fullText;

    /** 工具调用 ID（tool_call / tool_result 时） */
    private String toolCallId;

    /** 工具名称（tool_call 时） */
    private String toolName;

    /** 工具调用状态：pending / executing / success / error */
    private String status;

    /** 步数信息（round_end 时） */
    private StepInfo stepInfo;

    @Data
    public static class StepInfo {
        private int currentStep;
        private int maxSteps;
        private boolean shouldContinue;
        private int totalToolCalls;
    }

    // ===== 工厂方法 =====

    public static ReActEventDTO text(String content, String fullText) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("text");
        dto.setContent(content);
        dto.setFullText(fullText);
        return dto;
    }

    public static ReActEventDTO toolCall(String toolCallId, String toolName, String status) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("tool_call");
        dto.setToolCallId(toolCallId);
        dto.setToolName(toolName);
        dto.setStatus(status);
        return dto;
    }

    public static ReActEventDTO toolResult(String toolCallId, String content, String status) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("tool_result");
        dto.setToolCallId(toolCallId);
        dto.setContent(content);
        dto.setStatus(status);
        return dto;
    }

    public static ReActEventDTO roundEnd(int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("round_end");
        StepInfo si = new StepInfo();
        si.setCurrentStep(currentStep);
        si.setMaxSteps(maxSteps);
        si.setShouldContinue(shouldContinue);
        si.setTotalToolCalls(totalToolCalls);
        dto.setStepInfo(si);
        return dto;
    }

    public static ReActEventDTO done(String content) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("done");
        dto.setContent(content);
        return dto;
    }

    public static ReActEventDTO error(String content) {
        ReActEventDTO dto = new ReActEventDTO();
        dto.setEvent("error");
        dto.setContent(content);
        return dto;
    }

}

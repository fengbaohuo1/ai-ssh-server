package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTask;
import jainingfei.site.ai.agent.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 任务计划解析器——把规划器 LLM 的"自由文本输出"抢救成结构化计划（教程 2-10）。
 *
 * <p>模型不会老老实实只吐 JSON：常裹在 ```json 代码块里，或前后带一句解释。
 * 因此先用 {@code extractJson}（第一个 <code>{</code> 到最后一个 <code>}</code>）截出 JSON 主体，
 * 再逐字段解析与校验。
 *
 * <p>逐项容错策略：
 * <pre>
 *   缺 taskId        → 自动补 task-&lt;uuid&gt;（依赖关系不因缺 ID 而整体失败）
 *   缺 dependsOn     → 空依赖（可立即调度）
 *   缺 timeout/retry → 用值对象默认值（120s / 0 次）
 *   数字写成 "4"/4.0 → 按 Number/字符串容错解析（教程的 (Integer) 强转在这里会抛 ClassCastException）
 *   字段非法/超限    → 抛 IllegalArgumentException，由派发工具兜底成可读错误交给模型修正
 * </pre>
 */
@Slf4j
@Service
public class PlanParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析计划 JSON。
     *
     * @param content      规划器输出（可含 Markdown 包裹与解释文字）
     * @param allowedAgents 允许派发的子智能体白名单
     * @return 结构化任务计划
     * @throws IllegalArgumentException 输出不是合法计划 / 出现白名单外的智能体 / 任务描述为空
     */
    public DynamicTaskPlan parse(String content, List<String> allowedAgents) {
        String json = extractJson(content);
        if (StringUtils.isBlank(json)) {
            throw new IllegalArgumentException("invalid agent plan: no json object found");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid agent plan: " + e.getMessage());
        }

        JsonNode tasksNode = root.get("tasks");
        if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IllegalArgumentException("invalid agent plan: tasks is empty");
        }

        Set<String> allowed = new HashSet<>(allowedAgents == null ? List.of() : allowedAgents);
        List<DynamicTask> tasks = new ArrayList<>();
        for (JsonNode item : tasksNode) {
            DynamicTask task = new DynamicTask();

            String taskId = text(item, "taskId");
            task.setTaskId(StringUtils.isBlank(taskId) ? "task-" + UUID.randomUUID() : taskId.trim());

            String agentName = text(item, "agentName");
            if (StringUtils.isBlank(agentName)) {
                // 错误信息带上可选值：模型最常见的错法是漏字段或用别的键名，给出名单能显著提高自修正成功率
                throw new IllegalArgumentException("task agentName is blank: " + task.getTaskId()
                        + "（每个任务必须提供 agentName，可选值：" + String.join(",", allowed) + "）");
            }
            agentName = agentName.trim();
            if (!allowed.contains(agentName)) {
                throw new IllegalArgumentException("agent not allowed: " + agentName
                        + "（可选值：" + String.join(",", allowed) + "）");
            }
            task.setAgentName(agentName);

            String request = text(item, "request");
            if (StringUtils.isBlank(request)) {
                throw new IllegalArgumentException("task request is blank: " + task.getTaskId());
            }
            task.setRequest(request.trim());

            task.setDependsOn(stringList(item, "dependsOn"));

            Integer timeoutSeconds = intValue(item, "timeoutSeconds");
            if (timeoutSeconds != null) {
                task.setTimeoutSeconds(timeoutSeconds);
            }
            Integer maxRetries = intValue(item, "maxRetries");
            if (maxRetries != null) {
                task.setMaxRetries(maxRetries);
            }
            tasks.add(task);
        }

        Integer maxConcurrency = intValue(root, "maxConcurrency");
        Boolean failFast = boolValue(root, "failFast");
        Boolean requireConfirmation = boolValue(root, "requireConfirmation");

        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(tasks)
                .maxConcurrency(maxConcurrency != null ? maxConcurrency : 4)
                .failFast(failFast != null && failFast)
                .requireConfirmation(requireConfirmation != null && requireConfirmation)
                .build();
        log.info("规划器计划解析完成 | tasks:{} | maxConcurrency:{} | failFast:{}",
                tasks.size(), plan.getMaxConcurrency(), plan.getFailFast());
        return plan;
    }

    /** 提取首个 '{' 到最后一个 '}' 之间的 JSON 主体（兼容 Markdown 代码块与前后解释文字） */
    String extractJson(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    /** 文本字段（缺失/非文本 → null） */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : (value.isTextual() ? value.asText() : value.asText());
    }

    /**
     * 整数容错解析：数字 / 数字字符串都能吃（"4"、4、4.0）；
     * 其他垃圾值返回 null 走默认值，而不是把整份计划判死。
     */
    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return (int) Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException e) {
                log.warn("计划字段 [{}] 不是合法数字，忽略: {}", field, value.asText());
                return null;
            }
        }
        return null;
    }

    /** 布尔容错解析：true/false 与 "true"/"false" 均可 */
    private Boolean boolValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText().trim());
        }
        return null;
    }

    /** 字符串数组字段（非数组 → 空列表） */
    private List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> list = new ArrayList<>();
        if (value == null || !value.isArray()) {
            return list;
        }
        for (JsonNode item : value) {
            if (item != null && !item.isNull()) {
                list.add(item.asText());
            }
        }
        return list;
    }
}

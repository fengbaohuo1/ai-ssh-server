package jainingfei.site.ai.agent.cases.react;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态提示词后缀预算（case 层兜底裁剪）。
 *
 * <p>为什么在 case 层：后缀由 domain 的 {@code DynamicPromptBuilder} 逐节拼装，
 * 每节内容（系统环境、最近命令、里程碑、工具摘要、长期记忆）都没有长度上限，
 * 多来源叠加后可能长达数千字符，既挤占上下文预算，也使每轮"后缀段"持续膨胀。
 * 这里按节裁剪到固定预算，且优先保留错误/未绑定等关键行（它们决定模型下一步动作）。
 *
 * <p>规则：
 * <ol>
 *   <li>单节正文上限 {@value #MAX_SECTION_CHARS} 字符：错误/未绑定等关键行优先，
 *       其余按原顺序填充，最后标 {@value #TRIM_MARKER}</li>
 *   <li>总长仍超 {@value #MAX_SUFFIX_CHARS} 时，按 {@link #DROP_ORDER} 从次要到重要逐节丢弃，
 *       并在末尾注明省略了哪些节</li>
 *   <li>[用户意图]/[关键事件] 不主动丢弃（任务方向与失败信号最后保留）</li>
 *   <li>错误/未绑定等关键行先整体抢救到末尾 {@value #RESCUE_HEADER} 段：即使它所在的节
 *       被整节省略，模型仍能看到"为什么失败"</li>
 * </ol>
 *
 * <p>只做"预算兜底"，不改变后缀语义：未超预算时逐字节原样返回（缓存前缀不受影响）。
 */
public final class DynamicSuffixBudget {

    /** 后缀总长上限（字符） */
    public static final int MAX_SUFFIX_CHARS = 2000;

    /** 单个节的正文上限，防止某一节独占预算 */
    private static final int MAX_SECTION_CHARS = 800;

    /** 丢弃顺序（越靠前越先丢）；未列出的节默认不主动丢弃 */
    private static final List<String> DROP_ORDER = List.of(
            "[最近执行的命令]", "[长期记忆]", "[系统环境]", "[当前任务]", "[工具执行摘要]");

    /** 关键行标记：这些行在裁剪时必须优先保留（错误/未绑定信息决定模型下一步） */
    private static final String[] CRITICAL_MARKERS = {
            "失败", "错误", "异常", "未绑定", "超时", "拒绝", "error", "Error", "ERROR", "✗", "timeout"};

    /** 裁剪标记（也是硬截断时的尾部说明） */
    private static final String TRIM_MARKER = "…（本节内容已按预算裁剪）";

    /** 关键行抢救段的标题与预算（错误/未绑定等信息即使所在节被省略也必须留下） */
    private static final String RESCUE_HEADER = "[关键错误信息]";

    private static final int MAX_RESCUE_CHARS = 400;

    /** 已知节标题（只认这些，避免工具输出里的 "[...]" 文本被误切成节） */
    private static final java.util.Set<String> KNOWN_HEADERS = java.util.Set.of(
            "[系统环境]", "[最近执行的命令]", "[当前任务]", "[用户意图]", "[长期记忆]", "[关键事件]", "[工具执行摘要]");

    private DynamicSuffixBudget() {
    }

    /** 应用预算：不超过上限时原样返回，超限时按节裁剪 */
    public static String apply(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        if (suffix.length() <= MAX_SUFFIX_CHARS) {
            return suffix;
        }
        List<Section> sections = split(suffix);
        if (sections.isEmpty()) {
            return hardTruncate(suffix);
        }
        // 0. 先抢救关键行：错误/未绑定等信息可能正好位于随后会被整节省略的节里
        List<String> rescued = collectCriticalLines(suffix, MAX_RESCUE_CHARS);
        int rescueLen = rescued.isEmpty() ? 0 : RESCUE_HEADER.length() + 1 + sumLength(rescued);
        // 给抢救段留出空间，其余节只能用到剩下的预算
        int sectionBudget = MAX_SUFFIX_CHARS - rescueLen - (rescued.isEmpty() ? 0 : 2);
        for (Section section : sections) {
            section.body = trimBody(section.body, MAX_SECTION_CHARS);
        }
        for (String header : DROP_ORDER) {
            if (render(sections).length() <= sectionBudget) {
                break;
            }
            Section section = find(sections, header);
            if (section != null) {
                section.dropped = true;
            }
        }
        String result = render(sections);
        if (!rescued.isEmpty()) {
            result = (result.isEmpty() ? "" : result + "\n\n") + RESCUE_HEADER + "\n"
                    + String.join("\n", rescued);
        }
        return result.length() > MAX_SUFFIX_CHARS ? hardTruncate(result) : result;
    }

    /** 全后缀扫描关键行（错误/未绑定等），每条截断，总量受 max 限制 */
    private static List<String> collectCriticalLines(String suffix, int max) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (String raw : suffix.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || !isCritical(line)) {
                continue;
            }
            // "[任务失败信息]" 这类纯标签行只是"标签碰巧含关键字"，不是关键信息本身
            if (line.startsWith("[") && line.endsWith("]") && line.length() <= 40) {
                continue;
            }
            String clipped = clip(line, 160);
            if (used + clipped.length() + 1 > max) {
                break;
            }
            lines.add(clipped);
            used += clipped.length() + 1;
        }
        return lines;
    }

    private static int sumLength(List<String> lines) {
        int sum = 0;
        for (String line : lines) {
            sum += line.length() + 1;
        }
        return sum;
    }

    // ═══════════════════════════════════════════════════════════════
    //  解析 / 渲染
    // ═══════════════════════════════════════════════════════════════

    /** 按 "[标签]" 行切节（没有标签的前导内容保留为无头节） */
    private static List<Section> split(String suffix) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section(null, "");
        StringBuilder body = new StringBuilder();
        for (String line : suffix.split("\n", -1)) {
            if (isHeader(line)) {
                current.body = body.toString();
                if (current.header != null || !current.body.isBlank()) {
                    sections.add(current);
                }
                current = new Section(line.trim(), "");
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        current.body = body.toString();
        if (current.header != null || !current.body.isBlank()) {
            sections.add(current);
        }
        return sections;
    }

    /** 是否为节标题行（只认已知标签；"[用户意图已修正] ..." 这类正文行不算） */
    private static boolean isHeader(String line) {
        return line != null && KNOWN_HEADERS.contains(line.trim());
    }

    /** 渲染（保持原节顺序；丢弃的节只在末尾汇总提示一次） */
    private static String render(List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        List<String> omitted = new ArrayList<>();
        for (Section section : sections) {
            if (section.dropped) {
                if (section.header != null) {
                    omitted.add(section.header);
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (section.header != null) {
                sb.append(section.header).append('\n');
            }
            String body = section.body.stripTrailing();
            if (!body.isEmpty()) {
                sb.append(body).append('\n');
            }
        }
        if (!omitted.isEmpty()) {
            sb.append('\n').append("[动态上下文已裁剪: 省略 ").append(String.join(" ", omitted)).append("]");
        }
        return sb.toString().stripTrailing();
    }

    private static Section find(List<Section> sections, String header) {
        for (Section section : sections) {
            if (!section.dropped && header.equals(section.header)) {
                return section;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  正文裁剪
    // ═══════════════════════════════════════════════════════════════

    /** 单节正文裁剪：关键行（错误/未绑定等）优先，其余按原顺序填充剩余预算 */
    private static String trimBody(String body, int max) {
        if (body == null || body.length() <= max) {
            return body == null ? "" : body;
        }
        StringBuilder result = new StringBuilder();
        int budget = max;

        // 1. 关键行优先（最多占一半预算）
        int criticalBudget = Math.max(max / 2, 1);
        int criticalUsed = 0;
        for (String line : body.split("\n")) {
            if (line.isBlank() || !isCritical(line)) {
                continue;
            }
            int remaining = Math.min(criticalBudget - criticalUsed, budget - TRIM_MARKER.length() - 2);
            if (remaining <= 0) {
                break;
            }
            String clipped = clip(line, remaining);
            result.append(clipped).append('\n');
            criticalUsed += clipped.length() + 1;
            budget -= clipped.length() + 1;
        }

        // 2. 剩余预算按原顺序补普通行（跳过已保留的关键行）
        for (String line : body.split("\n")) {
            if (line.isBlank() || isCritical(line)) {
                continue;
            }
            int remaining = budget - TRIM_MARKER.length() - 2;
            if (remaining <= 0) {
                break;
            }
            String clipped = clip(line, remaining);
            if (clipped.isEmpty()) {
                break;
            }
            result.append(clipped).append('\n');
            budget -= clipped.length() + 1;
        }

        String trimmed = result.toString();
        int room = max - TRIM_MARKER.length() - 1;
        if (trimmed.length() > room) {
            // 尽量在行边界处截断，避免留下半行残句
            int cut = trimmed.lastIndexOf('\n', Math.max(room - 1, 0));
            trimmed = trimmed.substring(0, cut >= 0 ? cut : Math.max(room - 1, 0));
            if (!trimmed.endsWith("\n")) {
                trimmed = trimmed + "\n";
            }
        }
        return trimmed + TRIM_MARKER;
    }

    private static boolean isCritical(String line) {
        for (String marker : CRITICAL_MARKERS) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String line, int limit) {
        if (limit <= 0 || line == null || line.isEmpty()) {
            return "";
        }
        return line.length() <= limit ? line : line.substring(0, limit) + "…";
    }

    private static String hardTruncate(String suffix) {
        int room = MAX_SUFFIX_CHARS - TRIM_MARKER.length() - 1;
        if (suffix.length() <= room) {
            return suffix;
        }
        return suffix.substring(0, Math.max(room, 0)) + "\n" + TRIM_MARKER;
    }

    /** 一个后缀节：标题 + 正文；dropped=true 表示预算不足被省略 */
    private static final class Section {

        private final String header;

        private String body;

        private boolean dropped;

        private Section(String header, String body) {
            this.header = header;
            this.body = body;
        }
    }
}

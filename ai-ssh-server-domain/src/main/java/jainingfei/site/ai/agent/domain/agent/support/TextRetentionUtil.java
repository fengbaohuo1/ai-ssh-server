package jainingfei.site.ai.agent.domain.agent.support;

/**
 * 长文本保留工具——按 head/tail 窗口保留，中段以省略标记替代（对齐 dsh-output-retention 思路）。
 *
 * <p>为什么不用"掐头"：命令回显的头部（命令 + 起始输出）与工具结果的尾部
 * （最终结论/报错摘要）通常比中段更有信息量，且省略标记自带精确的丢弃计数，
 * 让模型知道内容被截断过、可要求补充，而不是误以为输出本来就这么短。
 *
 * <p>字符级截断（非字节）；上限为"保留的字符预算"，省略标记本身不计入预算（近似上限）。
 */
public final class TextRetentionUtil {

    private TextRetentionUtil() {
    }

    /**
     * 保留文本头尾（头部 60% + 尾部 40%），超限中段以省略标记替代。
     *
     * @param text     原始文本
     * @param maxChars 保留字符预算；text 不超过该值时原样返回
     * @return 保留后的文本（可能含省略标记），text 为 null 时返回 null
     */
    public static String retainHeadTail(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int headChars = maxChars * 3 / 5;
        int tailChars = maxChars - headChars;
        String head = text.substring(0, headChars);
        String tail = text.substring(text.length() - tailChars);
        int omitted = text.length() - maxChars;
        return head + "\n... (省略 " + omitted + " 字符) ...\n" + tail;
    }
}

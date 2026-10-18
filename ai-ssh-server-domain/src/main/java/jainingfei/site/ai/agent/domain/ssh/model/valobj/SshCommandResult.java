package jainingfei.site.ai.agent.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH 命令执行的结构化结果——AI 工具、回合治理与审计共用的值对象。
 *
 * <p>为什么不再用"一个 String"：旧实现只回传 stdout，stderr 直接丢给进程控制台，
 * 于是 {@code ls /not-exist} 与 {@code ls /tmp} 在模型眼里都是"空输出 = 成功"，
 * 失败被伪装成成功（假阳性）。把 stdout / stderr / 退出码 / 超时 / 截断拆开之后：
 * <ul>
 *   <li>成功判定改用<b>退出码</b>（{@code 0} 才是成功），文本启发式退居建议位置</li>
 *   <li>失败原因可以直接回传 stderr，模型不必靠猜</li>
 *   <li>超时 / 输出超限是显式字段，不再伪装成"输出为空"</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshCommandResult {

    /** 标准输出（已去除首尾空白） */
    private String stdout;

    /** 标准错误（已去除首尾空白；为空串表示无 stderr） */
    private String stderr;

    /** 退出码；null 表示通道未返回（超时 / 输出超限被主动断开 / 通道异常） */
    private Integer exitStatus;

    /** 是否因超过执行时限被主动断开 */
    private boolean timedOut;

    /** 是否因输出超过上限被主动断开（只拿到部分输出） */
    private boolean truncated;

    /** 是否拿到明确退出码 */
    public boolean hasExitStatus() {
        return exitStatus != null;
    }

    /** 是否明确成功（拿到退出码且为 0） */
    public boolean succeeded() {
        return exitStatus != null && exitStatus == 0 && !timedOut && !truncated;
    }

    /** 合并展示：stdout 为主体，stderr 非空时以 {@code [stderr]} 段落追加（模型与审计共用） */
    public String combinedOutput() {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (err.isEmpty()) {
            return out;
        }
        if (out.isEmpty()) {
            return "[stderr]\n" + err;
        }
        return out + "\n[stderr]\n" + err;
    }
}

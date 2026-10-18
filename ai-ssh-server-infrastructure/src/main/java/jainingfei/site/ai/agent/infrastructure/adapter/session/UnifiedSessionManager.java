package jainingfei.site.ai.agent.infrastructure.adapter.session;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ITerminalSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 统一终端会话管理器 — 双缓冲 + prompt 检测。
 * <pre>
 * JSch Session
 *   └─ ChannelShell (×1 per connectionId)
 *        ├─ reader 线程 → aiMode? → aiBuffer(notifyAll) + outputBuffer + listeners
 *        └─ AI executeCommand: 写命令 → wait aiBuffer → 检测 prompt → 返回
 * </pre>
 */
@Slf4j
@Component
public class UnifiedSessionManager implements ITerminalSessionPort {

    @Resource
    private ISshSessionPort sshSessionPort;

    /** connectionId → 统一终端会话 */
    private final ConcurrentHashMap<String, UnifiedTerminalSession> sessions = new ConcurrentHashMap<>();

    /** connectionId → AI 命令执行独立缓冲区 */
    private final ConcurrentHashMap<String, StringBuilder> aiBuffers = new ConcurrentHashMap<>();

    /** connectionId → 是否处于 AI 命令执行模式 */
    private final ConcurrentHashMap<String, Boolean> aiMode = new ConcurrentHashMap<>();

    /** connectionId → 上一次执行的命令缓存（防 LLM 重复执行） */
    private final ConcurrentHashMap<String, CachedResult> commandCache = new ConcurrentHashMap<>();

    private record CachedResult(String command, String output, long timestamp) {}
    private static final long CACHE_TTL_MS = 3_000; // 3 秒内同一命令不重复执行

    /** Shell prompt 检测标记 */
    private static final String[] PROMPT_MARKERS = {"$ ", "# ", "% "};

    /** ANSI 转义序列 */
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;]*[a-zA-Z]|\\u001B\\][^\\u0007\\u001B]*[\\u0007\\u001B]?");

    // ==================== WebSocket Handler 使用 ====================

    public UnifiedTerminalSession getOrCreate(String connectionId, int cols, int rows,
                                              Consumer<String> initialListener) {
        var session = sessions.computeIfAbsent(connectionId, id -> {
            log.info("创建终端会话 connectionId={}", id);
            var jschSession = sshSessionPort.getSession(id);
            if (jschSession == null || !jschSession.isConnected()) {
                throw new IllegalStateException("SSH 连接未建立: " + id);
            }
            try {
                var channel = (ChannelShell) jschSession.openChannel("shell");
                channel.setPtyType("xterm-256color");
                channel.setPtySize(cols, rows, cols * 8, rows * 16);
                channel.connect(10000);

                var listeners = new CopyOnWriteArrayList<Consumer<String>>();
                var s = new UnifiedTerminalSession(id, channel, listeners);
                aiBuffers.put(id, new StringBuilder());
                aiMode.put(id, false);
                startReader(s);
                log.info("终端会话创建成功 connectionId={}", id);
                return s;

            } catch (JSchException e) {
                throw new RuntimeException("创建终端会话失败: " + id, e);
            }
        });

        if (initialListener != null) {
            session.addListener(initialListener);
        }
        return session;
    }

    public UnifiedTerminalSession getOrCreate(String connectionId, int cols, int rows) {
        return getOrCreate(connectionId, cols, rows, null);
    }

    public UnifiedTerminalSession getSession(String connectionId) {
        var session = sessions.get(connectionId);
        if (session == null) {
            throw new IllegalArgumentException("终端会话不存在: " + connectionId);
        }
        return session;
    }

    // ==================== AI 工具使用 ====================

    public String executeCommand(String connectionId, String command) {
        // 去重：3 秒内重复执行同一命令直接返回缓存结果，阻止 LLM 循环
        var cached = commandCache.get(connectionId);
        if (cached != null && cached.command.equals(command)
                && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            log.info("[AI] 跳过重复命令: {}", command);
            return cached.output;
        }

        var session = getOrCreate(connectionId, 120, 24);
        var aiBuffer = aiBuffers.get(connectionId);
        if (aiBuffer == null) {
            throw new IllegalStateException("AI 缓冲区不存在: " + connectionId);
        }

        // 1. 切换到 AI 模式，清空 AI 缓冲区
        aiMode.put(connectionId, true);
        synchronized (aiBuffer) {
            aiBuffer.setLength(0);
        }

        try {
            // 2. 写命令到 shell
            var os = session.channel.getOutputStream();
            String fullCommand = command.endsWith("\n") ? command : command + "\n";
            os.write(fullCommand.getBytes(StandardCharsets.UTF_8));
            os.flush();
            log.debug("[AI] 命令已写入 connectionId={} command={}", connectionId, command);

            // 3. 等待输出稳定 + prompt 出现
            long deadline = System.currentTimeMillis() + 30_000;
            int stableCount = 0;
            final int STABLE_THRESHOLD = 3;          // 连续 N 轮无新数据
            final int POLL_MS = 100;

            while (System.currentTimeMillis() < deadline) {
                int currentLen;
                synchronized (aiBuffer) {
                    currentLen = aiBuffer.length();
                    if (currentLen == 0) {
                        try { aiBuffer.wait(POLL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }

                synchronized (aiBuffer) {
                    if (aiBuffer.length() > currentLen) {
                        stableCount = 0; // 有新数据
                    } else {
                        stableCount++;
                    }
                }

                if (stableCount >= STABLE_THRESHOLD) {
                    String output;
                    synchronized (aiBuffer) {
                        output = aiBuffer.toString();
                    }
                    if (output.length() > 0 && containsPrompt(output)) {
                        log.debug("[AI] 检测到 prompt，命令完成 connectionId={}", connectionId);
                        break;
                    }
                    // 无 prompt 但持续稳定，也认为完成（如 tail -f 类命令不适合 prompt 检测）
                    if (stableCount >= STABLE_THRESHOLD * 5) {
                        log.debug("[AI] 无 prompt 但输出持续稳定，视为完成");
                        break;
                    }
                }
            }

            // 4. 收集结果
            String result;
            synchronized (aiBuffer) {
                result = aiBuffer.toString();
                aiBuffer.setLength(0);
            }

            result = cleanOutput(result, command);
            commandCache.put(connectionId, new CachedResult(command, result, System.currentTimeMillis()));
            return result;

        } catch (IOException e) {
            throw new RuntimeException("AI 命令写入失败: " + command, e);
        } finally {
            // 5. 切回正常模式
            aiMode.put(connectionId, false);
        }
    }

    /**
     * ChannelExec 独立通道执行命令——无回显、无 ANSI、无 prompt，返回纯净 stdout。
     * AI 工具专用。每次新建 ChannelExec，执行完即关闭，不共享状态。
     */
    @Override
    public String execCommand(String connectionId, String command) {
        var jschSession = sshSessionPort.getSession(connectionId);
        if (jschSession == null || !jschSession.isConnected()) {
            throw new IllegalStateException("SSH 连接未建立: " + connectionId);
        }

        ChannelExec channel = null;
        try {
            channel = (ChannelExec) jschSession.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(System.err, true);

            var out = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.connect(10_000);

            while (!channel.isClosed()) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            String result = out.toString(StandardCharsets.UTF_8);

            log.debug("[EXEC] connectionId={} command={} outputLen={}", connectionId, command, result.length());
            return result.trim();

        } catch (Exception e) {
            log.error("[EXEC] 执行失败 connectionId={} command={}", connectionId, command, e);
            throw new RuntimeException("AI 命令执行失败: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    // ==================== 生命周期 ====================

    public void closeSession(String connectionId) {
        var session = sessions.remove(connectionId);
        if (session != null) {
            session.close();
            aiBuffers.remove(connectionId);
            aiMode.remove(connectionId);
            log.debug("终端会话已关闭 connectionId={}", connectionId);
        }
    }

    public boolean sessionExists(String connectionId) {
        var session = sessions.get(connectionId);
        return session != null && !session.isClosed();
    }

    /** 当前活跃终端会话数（运维概览使用） */
    public int activeSessionCount() {
        int count = 0;
        for (UnifiedTerminalSession session : sessions.values()) {
            if (session != null && !session.isClosed()) {
                count++;
            }
        }
        return count;
    }

    // ==================== 内部 ====================

    /** 后台读取线程：根据模式写入对应缓冲区 + WebSocket 推送 */
    private void startReader(UnifiedTerminalSession session) {
        var thread = new Thread(() -> {
            try {
                var in = session.channel.getInputStream();
                var buf = new byte[4096];
                while (!Thread.currentThread().isInterrupted()) {
                    var len = in.read(buf);
                    if (len > 0) {
                        var chunk = new String(buf, 0, len, StandardCharsets.UTF_8);

                        // 始终推送到 WebSocket 监听器（人能看到 AI 操作）
                        session.notifyListeners(chunk);

                        // AI 模式：额外写入独立缓冲区
                        if (Boolean.TRUE.equals(aiMode.get(session.connectionId))) {
                            var aiBuffer = aiBuffers.get(session.connectionId);
                            if (aiBuffer != null) {
                                synchronized (aiBuffer) {
                                    aiBuffer.append(chunk);
                                    aiBuffer.notifyAll();
                                }
                            }
                        }
                    } else if (len < 0) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!"Socket closed".equals(e.getMessage()) && !session.closed) {
                    log.warn("终端读取线程退出 connectionId={} error={}",
                            session.connectionId, e.getMessage());
                }
            }
        }, "terminal-reader-" + session.connectionId);
        thread.setDaemon(true);
        thread.start();
        session.readerThread = thread;
    }

    /** 检查输出是否已完成：最后一行末尾包含 shell prompt，且至少有 2 行（命令回显也含 prompt，需排除） */
    private static boolean containsPrompt(String output) {
        if (output == null || output.isEmpty()) return false;
        // 至少需要 2 行：回显行 + 输出/空行，否则仅回显行的 prompt 会被误判
        String[] lines = output.split("\\r?\\n");
        if (lines.length < 2) return false;
        String lastLine = lines[lines.length - 1].trim();
        for (String marker : PROMPT_MARKERS) {
            if (lastLine.contains(marker)) return true;
        }
        return lastLine.matches(".*@.*:[^\\n]*[$#]\\s*$");
    }

    /** 清理输出：去掉 ANSI 码、命令回显、末尾 prompt */
    private static String cleanOutput(String output, String command) {
        if (output == null || output.isEmpty()) return "";
        String s = ANSI.matcher(output).replaceAll("");
        s = s.replace("\r", "");

        String[] lines = s.split("\n");
        var result = new java.util.ArrayList<String>();
        int echoEndIdx = findEchoEnd(lines, command); // 命令回显可能因 PTY 换行跨多行

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (i <= echoEndIdx) continue;                        // 跳过回显行
            if (i == lines.length - 1 && isPromptLine(t)) continue; // 跳过末尾 prompt
            result.add(lines[i]);
        }

        return String.join("\n", result).trim();
    }

    /** 找到命令回显的结束行索引。PTY 换行使长命令跨多行。 */
    private static int findEchoEnd(String[] lines, String command) {
        if (command == null || command.isEmpty()) return -1;
        // 去所有空白后比较——PTY 换行会凭空插入空格，不能按词匹配
        String cmdCompact = command.replaceAll("\\s+", "");
        StringBuilder accumulated = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            accumulated.append(t);
            String accCompact = accumulated.toString().replaceAll("\\s+", "");
            if (accCompact.startsWith(cmdCompact)) return i;
            if (accCompact.length() > cmdCompact.length() + 20) break;
        }
        return -1;
    }

    private static boolean isPromptLine(String line) {
        for (String marker : PROMPT_MARKERS) {
            if (line.endsWith(marker.trim())) return true;
        }
        return line.matches(".*@.*:[^\\n]*[$#]\\s*$");
    }

    // ===================================================================
    // 内部类：统一终端会话
    // ===================================================================

    public static class UnifiedTerminalSession {
        private final String connectionId;
        private final ChannelShell channel;
        private final List<Consumer<String>> listeners;
        private volatile boolean closed;
        private Thread readerThread;

        UnifiedTerminalSession(String connectionId, ChannelShell channel,
                               List<Consumer<String>> listeners) {
            this.connectionId = connectionId;
            this.channel = channel;
            this.listeners = listeners;
        }

        // ==================== WebSocket 使用 ====================

        public void addListener(Consumer<String> listener) {
            listeners.add(listener);
        }

        public void removeListener(Consumer<String> listener) {
            listeners.remove(listener);
        }

        public void writeUserInput(String data) {
            try {
                var os = channel.getOutputStream();
                if (os == null) {
                    log.warn("OutputStream 为 null connectionId={}", connectionId);
                    return;
                }
                os.write(data.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                log.warn("写入用户输入失败 connectionId={}", connectionId, e);
            }
        }

        // ==================== 生命周期 ====================

        public boolean isClosed() {
            return closed;
        }

        public void close() {
            closed = true;
            if (readerThread != null) {
                readerThread.interrupt();
            }
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }

        // ==================== 内部 ====================

        private void notifyListeners(String text) {
            for (var listener : listeners) {
                try {
                    listener.accept(text);
                } catch (Exception ignored) {
                }
            }
        }
    }
}

package jainingfei.site.ai.agent.infrastructure.adapter.session;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ITerminalSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

            return cleanOutput(result, command);

        } catch (IOException e) {
            throw new RuntimeException("AI 命令写入失败: " + command, e);
        } finally {
            // 5. 切回正常模式
            aiMode.put(connectionId, false);
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

    /** 检查输出末尾是否包含 shell prompt */
    private static boolean containsPrompt(String output) {
        if (output == null || output.isEmpty()) return false;
        String tail = output.length() > 80 ? output.substring(output.length() - 80) : output;
        for (String marker : PROMPT_MARKERS) {
            if (tail.contains(marker)) return true;
        }
        // 也匹配 user@host:dir$ 格式
        return tail.matches("(?s).*@.*:[^\\n]*[$#]\\s*$");
    }

    /** 清理输出：去掉 ANSI 码、命令回显、末尾 prompt */
    private static String cleanOutput(String output, String command) {
        if (output == null || output.isEmpty()) return "";
        String s = ANSI.matcher(output).replaceAll("");
        s = s.replace("\r", "");

        String[] lines = s.split("\n");
        var result = new java.util.ArrayList<String>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();

            // 空行跳过
            if (t.isEmpty()) continue;

            // 第一行：如果是命令回显，跳过
            if (result.isEmpty() && isEchoLine(t, command)) continue;

            // 最后一行：如果是 prompt，跳过
            if (i == lines.length - 1 && isPromptLine(t)) continue;

            result.add(line);
        }

        return String.join("\n", result).trim();
    }

    private static boolean isEchoLine(String line, String command) {
        String cmd = command.trim();
        if (line.equals(cmd)) return true;
        if (cmd.length() > 10 && line.contains(cmd)) return true;
        return false;
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

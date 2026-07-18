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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 统一终端会话管理器 — 一个 {@code connectionId} 只维护一个 ChannelShell。
 * <p>
 * 合并 WebSocket 终端（人用）和 AI 工具（机器用）两条通路：
 * <pre>
 * JSch Session
 *   └─ UnifiedTerminalSession (×1 per connectionId)
 *        ├─ WebSocket Handler: 实时推送监听器 + writeUserInput()
 *        └─ AI 工具: write() + marker 等待 + consume 读
 * </pre>
 * AI 写命令期间持有 {@link ReentrantLock}，用户输入被自动丢弃，防止命令交错。
 */
@Slf4j
@Component
public class UnifiedSessionManager implements ITerminalSessionPort {

    @Resource
    private ISshSessionPort sshSessionPort;

    /** connectionId → 统一终端会话 */
    private final ConcurrentHashMap<String, UnifiedTerminalSession> sessions = new ConcurrentHashMap<>();

    // ==================== WebSocket Handler 使用 ====================

    /**
     * 获取或创建终端会话，并注册初始监听器（WebSocket 用）。
     * 监听器在读取线程启动前注册，确保推送包括 MOTD 在内的所有输出。
     * <p>
     * 注意：因为 {@link ConcurrentHashMap#computeIfAbsent} 在 key 已存在时不执行 lambda，
     * 所以返回现有会话后需要手动注册监听器。
     */
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

                var writer = new PrintWriter(new OutputStreamWriter(channel.getOutputStream()));
                var buffer = new StringBuilder();
                var listeners = new CopyOnWriteArrayList<Consumer<String>>();

                var s = new UnifiedTerminalSession(id, channel, writer, buffer, listeners);
                startReader(s);
                log.info("终端会话创建成功 connectionId={}", id);
                return s;

            } catch (JSchException | IOException e) {
                throw new RuntimeException("创建终端会话失败: " + id, e);
            }
        });

        // 无论是新建还是已有会话，监听器都要注册
        // computeIfAbsent 在会话已存在时不会执行 lambda，所以在这里补注册
        if (initialListener != null) {
            session.addListener(initialListener);
        }
        return session;
    }

    /**
     * 获取或创建终端会话（不带初始监听器，AI 工具调用时使用）。
     */
    public UnifiedTerminalSession getOrCreate(String connectionId, int cols, int rows) {
        return getOrCreate(connectionId, cols, rows, null);
    }

    /** 获取已存在的会话（不会自动创建） */
    public UnifiedTerminalSession getSession(String connectionId) {
        var session = sessions.get(connectionId);
        if (session == null) {
            throw new IllegalArgumentException("终端会话不存在: " + connectionId);
        }
        return session;
    }

    // ==================== AI 工具使用 ====================

    /**
     * 执行命令并等待输出。内部调用 {@link #getOrCreate}，终端会话不存在时会自动创建。
     *
     * @param connectionId SSH 连接 ID
     * @param command      Shell 命令
     * @return 命令输出文本（已去除 marker 标记行）
     */
    public String executeCommand(String connectionId, String command) {
        return getOrCreate(connectionId, 120, 24).executeCommand(command);
    }

    // ==================== 生命周期 ====================

    /** 关闭终端会话（SSH 断开连接时调用） */
    public void closeSession(String connectionId) {
        var session = sessions.remove(connectionId);
        if (session != null) {
            session.close();
            log.debug("终端会话已关闭 connectionId={}", connectionId);
        }
    }

    public boolean sessionExists(String connectionId) {
        var session = sessions.get(connectionId);
        return session != null && !session.isClosed();
    }

    // ==================== 内部 ====================

    /** 启动后台读取线程：ChannelShell InputStream → 共享缓冲区 + WebSocket 监听器 */
    private void startReader(UnifiedTerminalSession session) {
        var thread = new Thread(() -> {
            try {
                var in = session.channel.getInputStream();
                var buf = new byte[1024];
                while (!Thread.currentThread().isInterrupted()) {
                    var len = in.read(buf);
                    if (len > 0) {
                        var chunk = new String(buf, 0, len);
                        // AI 读缓冲区（后面通过 executeCommand 的 marker 机制消费）
                        synchronized (session.outputBuffer) {
                            session.outputBuffer.append(chunk);
                        }
                        // WebSocket 实时推送
                        session.notifyListeners(chunk);
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

    // ===================================================================
    // 内部类：统一终端会话
    // ===================================================================

    public static class UnifiedTerminalSession {
        private final String connectionId;
        private final ChannelShell channel;
        private final PrintWriter writer;
        private final StringBuilder outputBuffer;
        private final List<Consumer<String>> listeners;
        private final ReentrantLock writeLock = new ReentrantLock();
        private volatile boolean aiOperating;
        private volatile boolean closed;
        private Thread readerThread;

        UnifiedTerminalSession(String connectionId, ChannelShell channel, PrintWriter writer,
                               StringBuilder outputBuffer, List<Consumer<String>> listeners) {
            this.connectionId = connectionId;
            this.channel = channel;
            this.writer = writer;
            this.outputBuffer = outputBuffer;
            this.listeners = listeners;
        }

        // ==================== WebSocket 使用 ====================

        /** 注册实时输出监听器（WebSocket 连接时调用） */
        public void addListener(Consumer<String> listener) {
            listeners.add(listener);
        }

        /** 移除监听器（WebSocket 断开时调用） */
        public void removeListener(Consumer<String> listener) {
            listeners.remove(listener);
        }

        /** 写入用户输入（AI 操作期间自动丢弃，防止命令交错） */
        public void writeUserInput(String data) {
            if (aiOperating || writeLock.isLocked()) {
                return;
            }
            try {
                writer.print(data);
                writer.flush();
            } catch (Exception e) {
                log.warn("写入用户输入失败 connectionId={}", connectionId, e);
            }
        }

        // ==================== AI 工具使用 ====================

        /**
         * AI 执行命令：写命令 → 写唯一 marker → 轮询等待 marker 出现 → 返回输出。
         * <p>
         * 整个操作持有 {@link #writeLock}，阻止用户同时输入。
         */
        public String executeCommand(String command) {
            writeLock.lock();
            try {
                aiOperating = true;
                notifyListeners("\r\n[33m■ AI 正在操作: " + command + "[0m\r\n");

                // 清空旧数据，准备捕获本次命令输出
                synchronized (outputBuffer) {
                    outputBuffer.setLength(0);
                }

                // 生成唯一 marker（随机后缀防止误匹配）
                var marker = "__AI_END__" + UUID.randomUUID().toString().substring(0, 8) + "__";

                // 写命令
                writer.print(command + "\n");
                writer.flush();

                // 等输出开始到达
                Thread.sleep(300);

                // 写 marker
                writer.print("echo \"" + marker + "\"\n");
                writer.flush();

                // 轮询等待 marker
                var deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline) {
                    synchronized (outputBuffer) {
                        var content = outputBuffer.toString();
                        var idx = content.indexOf(marker);
                        if (idx >= 0) {
                            var output = content.substring(0, idx).trim();
                            outputBuffer.delete(0, idx + marker.length());
                            return output;
                        }
                    }
                    Thread.sleep(100);
                }

                throw new RuntimeException("AI 命令执行超时(30s): " + command);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI 命令执行被中断");
            } finally {
                aiOperating = false;
                notifyListeners("\r\n[32m■ AI 操作完成[0m\r\n");
                writeLock.unlock();
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
            try {
                writer.close();
            } catch (Exception ignored) {
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

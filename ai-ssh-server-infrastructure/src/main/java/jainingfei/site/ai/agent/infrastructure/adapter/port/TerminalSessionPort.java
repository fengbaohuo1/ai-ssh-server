package jainingfei.site.ai.agent.infrastructure.adapter.port;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ITerminalSessionPort;
import jainingfei.site.ai.agent.domain.ssh.adapter.port.ISshSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSch 终端会话端口实现 — 基于 ChannelShell 的终端会话管理。
 * <p>
 * 每个终端会话对应一个 JSch ChannelShell，后台线程持续读取输出到缓冲区，
 * {@link #read(String)} 返回缓冲区内容并清空。
 * <p>
 * 通过注入 {@link ISshSessionPort} 获取已连接的 JSch Session。
 */
@Slf4j
@Component
public class TerminalSessionPort implements ITerminalSessionPort {

    private final ISshSessionPort sshSessionPort;

    /** sessionId → 终端会话数据 */
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    public TerminalSessionPort(ISshSessionPort sshSessionPort) {
        this.sshSessionPort = sshSessionPort;
    }

    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        // 从 ISshSessionPort 获取已连接的 JSch Session
        Session jschSession = sshSessionPort.getSession(connectionId);
        if (jschSession == null || !jschSession.isConnected()) {
            throw new IllegalStateException("SSH 连接未建立: " + connectionId);
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            // 创建 ChannelShell
            ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setPtySize(cols, rows, cols * 8, rows * 16);

            // 获取流
            InputStream inputStream = channel.getInputStream();
            OutputStream outputStream = channel.getOutputStream();

            // 连接 channel
            channel.connect(10000);

            // 缓冲区
            StringBuilder buffer = new StringBuilder();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream));

            // 启动后台读取线程
            Thread readerThread = new Thread(() -> {
                byte[] tmp = new byte[1024];
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int len = inputStream.read(tmp);
                        if (len > 0) {
                            String chunk = new String(tmp, 0, len);
                            synchronized (buffer) {
                                buffer.append(chunk);
                            }
                        } else if (len < 0) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    if (!"Socket closed".equals(e.getMessage())) {
                        log.warn("终端读取线程退出 sessionId={} error={}", sessionId, e.getMessage());
                    }
                }
            }, "terminal-reader-" + sessionId);
            readerThread.setDaemon(true);
            readerThread.start();

            SessionData data = new SessionData(channel, writer, buffer, readerThread);
            sessions.put(sessionId, data);

            // 等待初始 MOTD 到达后清空，避免与后续命令输出混杂
            waitForOutput(buffer, 1500);
            synchronized (buffer) {
                log.info("终端初始输出已清空, bufferLength={}", buffer.length());
                buffer.setLength(0);
            }

            log.info("终端会话已打开 sessionId={} connectionId={}", sessionId, connectionId);
            return sessionId;

        } catch (JSchException | IOException e) {
            log.error("打开终端会话失败 connectionId={}", connectionId, e);
            throw new RuntimeException("打开终端会话失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String sessionId, String command) {
        SessionData data = sessions.get(sessionId);
        if (data == null || data.channel == null) {
            throw new IllegalArgumentException("终端会话不存在: " + sessionId);
        }

        try {
            data.writer.write(command + "\n");
            data.writer.flush();
            log.debug("终端写入 sessionId={} command={}", sessionId, command);
        } catch (Exception e) {
            log.error("终端写入失败 sessionId={} command={}", sessionId, command, e);
            throw new RuntimeException("终端写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String read(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data == null) {
            throw new IllegalArgumentException("终端会话不存在: " + sessionId);
        }

        // 等待输出稳定（连续 500ms 无新数据即认为完成）
        waitForOutput(data.buffer, 500);

        String output;
        synchronized (data.buffer) {
            output = data.buffer.toString();
            data.buffer.setLength(0);
        }
        return output;
    }

    /**
     * 等待缓冲区输出稳定：轮询直到指定时间内没有新数据到达
     */
    private void waitForOutput(StringBuilder buffer, long quietMs) {
        int prevLen;
        long waited = 0;
        do {
            synchronized (buffer) {
                prevLen = buffer.length();
            }
            try {
                Thread.sleep(200);
                waited += 200;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            synchronized (buffer) {
                if (buffer.length() == prevLen) {
                    break; // 输出稳定
                }
            }
        } while (waited < 5000); // 最多等 5 秒
    }

    @Override
    public void resize(String sessionId, int cols, int rows) {
        SessionData data = sessions.get(sessionId);
        if (data == null || data.channel == null) {
            throw new IllegalArgumentException("终端会话不存在: " + sessionId);
        }
        try {
            data.channel.setPtySize(cols, rows, cols * 8, rows * 16);
            log.debug("终端调整大小 sessionId={} cols={} rows={}", sessionId, cols, rows);
        } catch (Exception e) {
            log.error("终端调整大小失败 sessionId={}", sessionId, e);
        }
    }

    @Override
    public void closeSession(String sessionId) {
        SessionData data = sessions.remove(sessionId);
        if (data != null) {
            data.close();
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
    }

    /** 终端会话内部数据 */
    private static class SessionData {
        final ChannelShell channel;
        final PrintWriter writer;
        final StringBuilder buffer;
        final Thread readerThread;

        SessionData(ChannelShell channel, PrintWriter writer, StringBuilder buffer, Thread readerThread) {
            this.channel = channel;
            this.writer = writer;
            this.buffer = buffer;
            this.readerThread = readerThread;
        }

        void close() {
            readerThread.interrupt();
            writer.close();
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }
}

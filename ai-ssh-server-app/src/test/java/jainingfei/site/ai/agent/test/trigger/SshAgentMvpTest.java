package jainingfei.site.ai.agent.test.trigger;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.ConnectionStatusEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Scanner;
import java.util.UUID;

/**
 * SSH Agent MVP 集成测试（统一终端会话版本）。
 * <p>
 * 启动后自动连接服务器，在控制台输入自然语言指令，
 * AI Agent 自动调用 executeCommand 工具执行 SSH 命令，并返回分析结果。
 * <p>
 * 输出通过 SSE 实时显示在控制台，终端操作同步到共享 ChannelShell。
 * <p>
 * 使用前请修改下方 SSH_SERVER 配置为你自己的服务器信息。
 */
@Slf4j
@SpringBootTest
public class SshAgentMvpTest {

    // ==================== 配置区：改成你自己的服务器 ====================
    private static final String SSH_HOST = "106.54.3.66";
    private static final int SSH_PORT = 22;
    private static final String SSH_USERNAME = "root";
    private static final String SSH_PASSWORD = "Jnf-1607292542";
    // =================================================================

    // Agent 配置（对应 ssh-agent.yml 中的 agent-id）
    private static final String AGENT_ID = "100004";
    private static final String USER_ID = "mvp-test";

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private IChatService chatService;

    /** SSH 连接 ID（同时也是统一终端会话的 key） */
    private String connectionId;
    /** AI 对话会话 ID */
    private String chatSessionId;

    /**
     * 初始化：创建连接 → 建立 SSH → 创建对话会话 → 绑定 connectionId
     * <p>
     * 不再需要 {@code openTerminal}，统一终端会话在第一次 executeCommand 时自动创建。
     */
    @BeforeEach
    public void setUp() {
        log.info("========== MVP 初始化开始 ==========");

        String connectionId = UUID.randomUUID().toString().replace("-", "");

        // 1. 创建 SSH 连接记录
        SshConnectionEntity connEntity = SshConnectionEntity.builder()
                .connectionId(connectionId)
                .connectionName("MVP 测试连接")
                .host(SSH_HOST)
                .port(SSH_PORT)
                .username(SSH_USERNAME)
                .authType(AuthTypeEnum.PASSWORD)
                .password(SSH_PASSWORD)
                .encrypted(false)
                .status(ConnectionStatusEnum.DISCONNECTED)
                .userId(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .deleted(false)
                .build();

        SshConnectionConfigEntity configEntity = SshConnectionConfigEntity.builder()
                .connectionId(connectionId)
                .connectTimeout(15)
                .keepaliveInterval(30)
                .build();

        sshConnectionService.createConnection(connEntity, configEntity);
        this.connectionId = connectionId;
        log.info("1. 连接记录创建成功 connectionId={}", connectionId);

        // 2. 建立 SSH 连接
        boolean connected = sshConnectionService.connect(connectionId);
        if (!connected) {
            throw new RuntimeException("SSH 连接失败，请检查 host/port/账号/密码");
        }
        log.info("2. SSH 连接成功 host={}:{}", SSH_HOST, SSH_PORT);

        // 3. 创建 AI 对话会话
        chatSessionId = chatService.createSession(AGENT_ID, USER_ID);
        log.info("3. AI 对话会话已创建 chatSessionId={}", chatSessionId);

        // 4. connectionId 由 ChatService.handleMessageStream() 写入 ADK 会话 state
        log.info("4. SSH 连接已就绪 connectionId={}（每次对话时写入会话状态）", connectionId);
        log.info("========== MVP 初始化完成，可以开始对话了 ==========\n");
    }

    /**
     * 交互式对话：在控制台输入自然语言，AI Agent 执行命令并返回结果。
     * <p>
     * 运行后在控制台输入：
     * - "查看服务器系统信息"
     * - "检查 docker 是否安装"
     * - "查看磁盘和内存使用情况"
     * - "exit" 退出
     */
    @Test
    public void test_ai_shell() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\n你 > ");
            String input = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("再见！");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            try {
                System.out.print("AI > ");
                chatService.handleMessageStream(
                                AGENT_ID, USER_ID, chatSessionId, input, connectionId)
                        .blockingForEach(event -> {
                            String text = event.stringifyContent();
                            if (!text.isEmpty()) {
                                System.out.print(text);
                            }
                        });
                System.out.println();

            } catch (Exception e) {
                log.error("对话异常", e);
                System.out.println("出错: " + e.getMessage());
            }
        }

        scanner.close();
    }

    /**
     * 单次对话测试：不交互，直接执行一条指令看结果。
     */
    @Test
    public void test_singleChat() {
        String message = "查看服务器系统信息，包括操作系统版本、CPU、内存";
        log.info("发送消息: {}", message);

        System.out.print("\nAI > ");
        chatService.handleMessageStream(AGENT_ID, USER_ID, chatSessionId, message, connectionId)
                .blockingForEach(event -> {
                    String text = event.stringifyContent();
                    if (!text.isEmpty()) {
                        System.out.print(text);
                    }
                });
        System.out.println();
    }

    /**
     * 直接测试 SSH 命令执行（不经过 AI），验证 SSH 终端链路是否通畅。
     * <p>
     * 现在使用 connectionId 代替旧的 terminalSessionId。
     */
    @Test
    public void test_directCommand() {
        log.info("直接执行 SSH 命令测试（不经过 AI）");

        String[] commands = {
                "whoami",
                "hostname",
                "uname -a",
                "df -h",
                "free -m"
        };

        for (String cmd : commands) {
            System.out.println("\n--- 执行: " + cmd + " ---");
            // 使用 connectionId 直接执行（内部自动创建/获取统一终端会话）
            String output = sshTerminalService.executeCommand(connectionId, cmd);
            System.out.println(output);
        }
    }
}

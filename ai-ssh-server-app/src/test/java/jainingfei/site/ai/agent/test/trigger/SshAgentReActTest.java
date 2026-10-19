package jainingfei.site.ai.agent.test.trigger;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.api.dto.ChatRequestDTO;
import jainingfei.site.ai.agent.cases.react.IAIAgentReActServiceCase;
import jainingfei.site.ai.agent.domain.agent.service.IChatService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jainingfei.site.ai.agent.domain.ssh.model.aggregate.SshConnectionAggregate;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionConfigEntity;
import jainingfei.site.ai.agent.domain.ssh.model.entity.SshConnectionEntity;
import jainingfei.site.ai.agent.domain.ssh.model.valobj.enums.AuthTypeEnum;
import jainingfei.site.ai.agent.domain.ssh.service.ISshConnectionService;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SSH Agent ReAct 真实链路测试 — 通过 Case 层节点链路执行。
 *
 * <p>与 SshAgentMvpTest 使用同一 SSH 服务器，但走 ReAct Case 执行模型。
 *
 * <p>所有 @Test 方法均为非阻塞自动化测试（无 Scanner、无死循环、CountDownLatch 带超时）。
 */
@Slf4j
@SpringBootTest
public class SshAgentReActTest {

    // ==================== SSH 服务器配置 ====================
    private static final String SSH_HOST = "192.0.2.10";
    private static final int SSH_PORT = 22;
    private static final String SSH_USERNAME = "root";
    private static final String SSH_PASSWORD = "Jnf-1607292542";
    // =================================================================

    // Agent 配置（对应 ssh-agent.yml 中的 agent-id）
    private static final String AGENT_ID = "100004";
    private static final String USER_ID = "react-test";

    @Resource
    private ISshConnectionService sshConnectionService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

    /** SSH 连接 ID */
    private String connectionId;
    /** AI 对话会话 ID */
    private String chatSessionId;

    @BeforeEach
    public void init() {
        log.info("========== ReAct Test 初始化开始 ==========");

        // 1. 创建 SSH 连接记录
        SshConnectionEntity connEntity = SshConnectionEntity.builder()
                .connectionName("ReAct测试连接")
                .host(SSH_HOST)
                .port(SSH_PORT)
                .username(SSH_USERNAME)
                .authType(AuthTypeEnum.PASSWORD)
                .password(SSH_PASSWORD)
                .userId(USER_ID)
                .build();

        SshConnectionConfigEntity configEntity = SshConnectionConfigEntity.builder()
                .connectTimeout(15)
                .keepaliveInterval(30)
                .build();

        sshConnectionService.createConnection(connEntity, configEntity);
        connectionId = connEntity.getConnectionId();
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

        log.info("========== ReAct Test 初始化完成 ==========\n");
    }

    /**
     * 同步 ReAct 对话 — 发送一条指令，阻塞等待完整结果。
     */
    @Test
    public void test_singleChat() {
        String message = "查看服务器系统信息，包括操作系统版本";
        log.info("发送消息: {}", message);

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId(AGENT_ID);
        requestDTO.setUserId(USER_ID);
        requestDTO.setSessionId(chatSessionId);
        requestDTO.setMessage(message);
        requestDTO.setTerminalSessionId(connectionId);

        System.out.print("\nAI > ");
        String result = reactServiceCase.chat(requestDTO);
        System.out.println(result);

        log.info("ReAct 同步对话完成 contentLength={}", result != null ? result.length() : 0);
    }

    /**
     * 流式 ReAct 对话 — 通过 SSE 接收逐步输出（CountDownLatch 带超时）。
     */
    @Test
    public void test_streamChat() throws Exception {
        String message = "检查 docker 是否安装";
        log.info("发送流式消息: {}", message);

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId(AGENT_ID);
        requestDTO.setUserId(USER_ID);
        requestDTO.setSessionId(chatSessionId);
        requestDTO.setMessage(message);
        requestDTO.setTerminalSessionId(connectionId);

        // 调用流式接口，获取 SseEmitter
        SseEmitter emitter = reactServiceCase.chatStream(requestDTO);

        // 使用 CountDownLatch 等待异步流完成（带超时保护）
        CountDownLatch latch = new CountDownLatch(1);

        emitter.onCompletion(() -> {
            log.info("SSE 流完成");
            latch.countDown();
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 流超时");
            latch.countDown();
        });

        emitter.onError(throwable -> {
            log.error("SSE 流错误", throwable);
            latch.countDown();
        });

        // 等待 ReAct 循环完成（最多 3 分钟）
        boolean completed = latch.await(3, TimeUnit.MINUTES);

        if (!completed) {
            log.warn("等待超时，ReAct 可能未完成");
        }

        log.info("流式对话结束 completed={}", completed);
    }

    /**
     * 直接测试 SSH 命令执行（不经过 AI），验证 SSH 链路通畅。
     */
    @Test
    public void test_directCommand() {
        log.info("直接执行 SSH 命令测试（不经过 AI）");

        String[] commands = { "whoami", "hostname", "uname -a" };

        for (String cmd : commands) {
            System.out.println("\n--- 执行: " + cmd + " ---");
            String output = sshTerminalService.execCommand(connectionId, cmd);
            System.out.println(output);
        }
    }

}

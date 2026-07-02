package jainingfei.site.ai.agent.test.infrastructure;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jainingfei.site.ai.agent.infrastructure.adapter.port.SshSessionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * ClassName:SshSessionPortManualTest
 * Project:ai-ssh-server
 * Package: jainingfei.site.ai.agent.test.ssh
 *
 * @Author 贾宁飞
 * @Description
 * @Create 2026/7/1 下午5:52
 * @Version 1.0
 */
public class SshSessionPortManualTest {
    /**
     * 这里的 IP、账号要换成你自己的云服务器；
     * <a href="http://618.gaga.plus/">http://618.gaga.plus/</a>
     */
    private static final String HOST = "106.54.3.66";
    private static final int PORT = 22;
    private static final String USERNAME = "root";
    private static final String CONNECTION_ID = "manual-ssh-test";

    @Test
    public void test_connect_and_execute_commands_interactively() throws Exception {
        runInteractiveDemo();
    }

    public static void main(String[] args) throws Exception {
        runInteractiveDemo();
    }

    private static void runInteractiveDemo() throws Exception {
        SshSessionPort sshSessionPort = new SshSessionPort();
        String password = readPasswordFromConsole();

        Assertions.assertTrue(password != null && !password.isBlank(), "密码不能为空");

        boolean connected = sshSessionPort.connect(
                CONNECTION_ID, HOST, PORT, USERNAME, password, null);
        Assertions.assertTrue(connected, "SSH 连接失败，请检查 IP、账号或密码是否正确");

        try {
            Session session = sshSessionPort.getSession(CONNECTION_ID);


            printConnectionBanner(session);
            printCommandResult(executeCommand(session, "hostname && whoami && pwd"));
            interactiveCommandLoop(session);
        } finally {
            sshSessionPort.disconnect(CONNECTION_ID);
            System.out.println("\nSSH 连接已关闭。");
        }
    }

    private static String readPasswordFromConsole() {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("请输入云服务器密码: ");
            return password == null ? null : new String(password);
        }
        System.out.print("请输入云服务器密码: ");
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        return scanner.nextLine();
    }

    private static void printConnectionBanner(Session session) {
        System.out.println("\n================ SSH 连接成功 ================");
        System.out.println("Host      : " + session.getHost());
        System.out.println("Port      : " + session.getPort());
        System.out.println("User      : " + session.getUserName());
        System.out.println("ServerVer : " + session.getServerVersion());
        System.out.println("ClientVer : " + session.getClientVersion());
        System.out.println("输入命令后回车即可执行，例如: ls、ls -la");
        System.out.println("输入 exit 或 quit 结束测试");
        System.out.println("============================================");
    }

    private static void interactiveCommandLoop(Session session) throws Exception {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        while (true) {
            System.out.print("\nssh> ");
            if (!scanner.hasNextLine()) break;
            String command = scanner.nextLine();
            if (command == null || command.isBlank()) continue;
            if ("exit".equalsIgnoreCase(command) || "quit".equalsIgnoreCase(command)) break;
            printCommandResult(executeCommand(session, command));
        }
    }

    private static CommandResult executeCommand(Session session, String command) throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            channel.connect(10_000);

            while (!channel.isClosed()) {
                Thread.sleep(200L);
            }

            return new CommandResult(
                    command,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    channel.getExitStatus()
            );
        } catch (JSchException e) {
            throw new IllegalStateException("执行命令失败: " + command, e);
        } finally {
            if (channel != null) channel.disconnect();
        }
    }

    private static void printCommandResult(CommandResult result) {
        System.out.println("\n$ " + result.command());
        System.out.println("exitCode: " + result.exitCode());

        if (!result.stdout().isBlank()) {
            System.out.println("[stdout]");
            System.out.print(result.stdout());
            if (!result.stdout().endsWith("\n")) System.out.println();
        }

        if (!result.stderr().isBlank()) {
            System.out.println("[stderr]");
            System.out.print(result.stderr());
            if (!result.stderr().endsWith("\n")) System.out.println();
        }
    }

    private record CommandResult(String command, String stdout, String stderr, int exitCode) {
    }
}

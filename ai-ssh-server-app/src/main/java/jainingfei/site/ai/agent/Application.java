package jainingfei.site.ai.agent;

import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import jainingfei.site.ai.agent.infrastructure.config.AiSshHome;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 应用启动入口。
 *
 * <p>桌面端以「jar + JRE」本地单机形态运行，因此启动前必须先确定数据目录并完成
 * 可写目录 / 配置模板初始化——这一步必须发生在 {@link SpringApplication#run} 之前，
 * 因为 application-local.yml 里的 {@code ${AI_SSH_HOME}} 占位符（H2 文件库路径、
 * {@code spring.config.import} 的外部配置）在容器启动时就会解析。
 *
 * <p>解析优先级与目录布局见 {@link AiSshHome}。
 */
@Slf4j
@SpringBootApplication
@Configurable
@MapperScan("jainingfei.site.ai.agent.infrastructure.dao")
public class Application {

    /** 主配置模板（classpath 只读兜底；首次启动复制到 ${AI_SSH_HOME}/config/agent.yml） */
    private static final String AGENT_CONFIG_TEMPLATE = "agent/ssh-agent.yml";

    public static void main(String[] args) {
        prepareAppHome();

        // 必须透传 args：否则 --server.port / --ai.* 等命令行覆盖全部失效（排查启动配置时会非常隐蔽）
        SpringApplication.run(Application.class, args);
    }

    /**
     * 应用数据目录准备：建目录 → 规范化绝对路径写入 {@code AI_SSH_HOME} 系统属性 → 初始化 agent.yml。
     *
     * <p>异常处理原则：目录不可写是硬失败（数据无处可放，必须让用户看到日志后退出）；
     * 模板复制失败只记录 ERROR 不阻断启动——配置页会再次尝试初始化，用户仍能打开配置界面。
     */
    private static void prepareAppHome() {
        Path home;
        try {
            home = AiSshHome.prepare();
        } catch (Exception e) {
            log.error("应用数据目录初始化失败（请检查 AI_SSH_HOME 是否可写）: {}", AiSshHome.resolve(), e);
            // 抛出而非继续：H2 文件库与外部配置都依赖该目录，带病启动只会在更隐蔽的地方失败
            throw new IllegalStateException("应用数据目录初始化失败: " + AiSshHome.resolve(), e);
        }
        log.info("应用数据目录: {}（data/ config/ logs/）", home);

        try {
            boolean copied = AiSshHome.copyClasspathTemplateIfMissing(
                    AGENT_CONFIG_TEMPLATE, AiSshHome.agentConfigFile());
            if (copied) {
                log.info("已从模板 {} 初始化主配置: {}", AGENT_CONFIG_TEMPLATE, AiSshHome.agentConfigFile());
            } else {
                log.info("主配置已存在，保留用户配置: {}", AiSshHome.agentConfigFile());
            }
        } catch (Exception e) {
            // 不阻断启动：配置页 GET /api/v1/agent/config/file 会再次尝试初始化并给出具体错误
            log.error("初始化主配置 agent.yml 失败（配置页可重试）: {} | target: {}",
                    e.getMessage(), AiSshHome.agentConfigFile(), e);
        }
    }

    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }
}

package jainingfei.site.ai.agent.infrastructure.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 应用数据目录（AI_SSH_HOME）解析与初始化——桌面端「jar + JRE + 本地数据」模式的唯一约定。
 *
 * <p>目录布局（首次启动自动创建）：
 * <pre>
 *   ${AI_SSH_HOME}/data/     H2 文件库（ai-ssh.mv.db）
 *   ${AI_SSH_HOME}/config/   agent.yml（主配置）与 llm.yml（模型覆盖层）
 *   ${AI_SSH_HOME}/logs/     logback 滚动日志
 * </pre>
 *
 * <p>解析优先级：环境变量 {@code AI_SSH_HOME} &gt; 系统属性 {@code AI_SSH_HOME}
 * &gt; {@code ${user.home}/.ai-ssh}。解析结果会以「绝对路径 + 正斜杠」写回系统属性，
 * 这样 Spring 的 {@code ${AI_SSH_HOME}} 占位符在 Windows 上也能安全拼进
 * {@code file:} / {@code jdbc:h2:file:} 串（反斜杠会被当成转义字符）。
 */
@Slf4j
public final class AiSshHome {

    /** 应用数据根目录的环境变量 / 系统属性名 */
    public static final String HOME_PROPERTY = "AI_SSH_HOME";

    /** 未显式配置时的默认目录名（用户主目录下） */
    private static final String DEFAULT_DIR_NAME = ".ai-ssh";

    /** 需要在首次启动时创建的子目录 */
    private static final List<String> SUB_DIRS = List.of("data", "config", "logs");

    private AiSshHome() {
    }

    /** 解析数据根目录（不创建目录、不写系统属性） */
    public static Path resolve() {
        String configured = System.getenv(HOME_PROPERTY);
        if (isBlank(configured)) {
            configured = System.getProperty(HOME_PROPERTY);
        }
        Path home = isBlank(configured)
                ? Paths.get(System.getProperty("user.home", "."), DEFAULT_DIR_NAME)
                : Paths.get(configured.trim());
        return home.toAbsolutePath().normalize();
    }

    /** 当前数据根目录 */
    public static Path home() {
        return resolve();
    }

    /** 配置目录（agent.yml / llm.yml） */
    public static Path configDir() {
        return resolve().resolve("config");
    }

    /** 主配置 ${AI_SSH_HOME}/config/agent.yml */
    public static Path agentConfigFile() {
        return configDir().resolve("agent.yml");
    }

    /** 模型覆盖层 ${AI_SSH_HOME}/config/llm.yml */
    public static Path llmOverlayFile() {
        return configDir().resolve("llm.yml");
    }

    /**
     * 启动期准备：创建 data/config/logs 目录，并把规范化路径写回系统属性。
     *
     * @return 规范化后的数据根目录
     * @throws IOException 目录创建失败（调用方必须记录日志，不能静默忽略）
     */
    public static Path prepare() throws IOException {
        Path home = resolve();
        for (String dir : SUB_DIRS) {
            Files.createDirectories(home.resolve(dir));
        }
        return normalizeAndExpose(home);
    }

    /** 把绝对路径以正斜杠形式写回系统属性，返回同一路径 */
    public static Path normalizeAndExpose(Path home) {
        String normalized = home.toAbsolutePath().normalize().toString().replace('\\', '/');
        System.setProperty(HOME_PROPERTY, normalized);
        return Paths.get(normalized);
    }

    /**
     * 若目标文件不存在，从 classpath 复制一份模板。
     *
     * @param classpathTemplate classpath 资源路径，例如 {@code agent/ssh-agent.yml}
     * @param target            目标文件
     * @return true = 本次确实复制了模板；false = 目标已存在，未改动
     * @throws IOException 模板不存在或复制失败
     */
    public static boolean copyClasspathTemplateIfMissing(String classpathTemplate, Path target) throws IOException {
        if (Files.exists(target)) {
            return false;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ClassLoader classLoader = AiSshHome.class.getClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(classpathTemplate)) {
            if (in == null) {
                throw new IOException("classpath 模板不存在: " + classpathTemplate);
            }
            Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    in.transferTo(out);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        log.info("已从 classpath 模板初始化配置文件 | template:{} | target:{}", classpathTemplate, target);
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

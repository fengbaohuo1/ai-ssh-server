package jainingfei.site.ai.agent.infrastructure.config;

/**
 * 配置文件校验失败——controller 捕获后返回 {@code code=0002}（HTTP 仍 200），
 * info 为具体可操作错误（YAML 语法、缺 ai.agent.config.tables、未知通道、缺 API Key 等）。
 */
public class AgentConfigValidationException extends RuntimeException {

    public AgentConfigValidationException(String message) {
        super(message);
    }
}

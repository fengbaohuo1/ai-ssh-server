package jainingfei.site.ai.agent.domain.agent.service.armory.catalog;

import com.google.adk.agents.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子智能体注册中心——装配完成后集中存放"某个配置表下的全部智能体"，供派发链路按名查找（教程 2-10）。
 *
 * <p>装配期（{@code RunnerNode}）登记，运行期（派发工具 / 执行器）查找：
 * <pre>
 *   RunnerNode:  agentCatalog.register(appName, agentGroup)
 *   派发时:      agentCatalog.find(context.getAppName(), task.getAgentName())
 *                     └─ 未命中再 findByName 全局兜底（只用于容错，正常路径应精确命中）
 * </pre>
 *
 * <p>与教程的差异：教程用<b>数字 agentId</b>（YAML agent.agent-id）登记，但派发时传的是
 * ADK 的 <b>agentName</b>（toolContext.agentName()），两边键不一致导致精确查找永远 miss、
 * 每次都要全局兜底，多个配置表存在同名子智能体时会串号。
 * 我们用 {@code appName}（ADK App 名，派发侧可由 toolContext.invocationContext().appName()
 * 稳定取得）作键，父子表之间天然隔离。
 */
@Slf4j
@Component
public class AgentCatalog {

    /** appName → (agentName → agent) */
    private final Map<String, Map<String, BaseAgent>> agentsByAppName = new ConcurrentHashMap<>();

    /**
     * 登记一次装配产出的全部智能体（重复装配覆盖式替换，便于配置热更新）。
     *
     * @param appName    配置表 app-name
     * @param agentGroup 装配产出的智能体分组（name → agent）
     */
    public void register(String appName, Map<String, BaseAgent> agentGroup) {
        if (appName == null || agentGroup == null) {
            return;
        }
        Map<String, BaseAgent> snapshot = new ConcurrentHashMap<>(agentGroup);
        agentsByAppName.put(appName, snapshot);
        log.info("子智能体注册 | appName:{} | agents:{}", appName, snapshot.keySet());
    }

    /**
     * 在指定配置表内精确查找智能体。
     *
     * @param appName   配置表 app-name
     * @param agentName 智能体名
     */
    public Optional<BaseAgent> find(String appName, String agentName) {
        if (appName == null || agentName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentsByAppName.get(appName))
                .map(group -> group.get(agentName));
    }

    /**
     * 跨配置表按名全局查找（兜底，找到第一个即返回）。
     * <p>正常路径应走 {@link #find}；走到这里说明 appName 缺失或不匹配，记 WARN 便于排查配置问题。
     */
    public Optional<BaseAgent> findByName(String agentName) {
        if (agentName == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, Map<String, BaseAgent>> entry : agentsByAppName.entrySet()) {
            BaseAgent agent = entry.getValue().get(agentName);
            if (agent != null) {
                log.warn("子智能体全局兜底查找命中 | agentName:{} | appName:{}（建议检查 appName 是否传对）",
                        agentName, entry.getKey());
                return Optional.of(agent);
            }
        }
        return Optional.empty();
    }

    /** 列出某个配置表下已登记的全部智能体；未登记返回空 Map */
    public Map<String, BaseAgent> list(String appName) {
        return agentsByAppName.getOrDefault(appName, Map.of());
    }

}

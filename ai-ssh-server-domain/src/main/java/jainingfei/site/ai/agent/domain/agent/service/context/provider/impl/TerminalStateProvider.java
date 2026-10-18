package jainingfei.site.ai.agent.domain.agent.service.context.provider.impl;

import jainingfei.site.ai.agent.domain.agent.service.context.provider.ContextProvider;
import jainingfei.site.ai.agent.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端状态上下文提供者（order=10，最先执行）。
 *
 * <p>通过 SSH 终端实时采集远程服务器的环境信息（OS / 用户 / 工作目录），
 * 让模型"知道自己在哪台机器上操作"。该逻辑从 PromptService 下沉至此，
 * PromptService 不再直接依赖 ISshTerminalService。
 *
 * <p>容错设计：每条命令独立 try-catch（safeExec），单条失败仅该字段留空——
 * 环境采集是"锦上添花"，绝不阻断主流程。
 *
 * <p>性能设计：一回合内动态上下文可能被多次组装，而环境信息在几秒内不会变化，
 * 因此按连接做短 TTL 缓存（{@link #CACHE_TTL_MS}），把"每回合 3 次同步 SSH"
 * 收敛成"每 TTL 窗口最多 3 次"。命中缓存返回副本，调用方修改不会污染缓存。
 * 连接状态变化（重连/切目录等）最多滞后一个 TTL 窗口，可接受。
 */
@Slf4j
@Component
public class TerminalStateProvider implements ContextProvider {

    /** 环境信息缓存有效期：3 秒足够覆盖同一回合内的多次上下文组装。 */
    private static final long CACHE_TTL_MS = 3_000L;

    /** 缓存上限：防止连接 ID 无限增长（按 LRU 淘汰）。 */
    private static final int MAX_CACHE_ENTRIES = 64;

    @Resource
    private ISshTerminalService sshTerminalService;

    /** connectionId → 环境快照（access-order LRU，容量有界）。 */
    private final Map<String, CachedState> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedState> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    @Override
    public String getName() {
        return "terminal-state";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return new HashMap<>();
        }

        long now = System.currentTimeMillis();
        CachedState cached = cache.get(terminalSessionId);
        if (cached != null && now - cached.at() < cacheTtlMs()) {
            log.debug("环境采集命中缓存 | connection={} | age={}ms", terminalSessionId, now - cached.at());
            return new HashMap<>(cached.state());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("osInfo", safeExec(terminalSessionId, "uname -srm"));
        result.put("currentUser", safeExec(terminalSessionId, "whoami"));
        result.put("currentDirectory", safeExec(terminalSessionId, "pwd"));
        // 缓存与返回都放独立副本：调用方修改返回值不能污染缓存，缓存内容也不会外泄
        cache.put(terminalSessionId, new CachedState(now, Collections.unmodifiableMap(new HashMap<>(result))));
        return result;
    }

    /**
     * 主动失效某个连接的环境缓存——连接重连、远程目录/用户确定变化后调用。
     *
     * @param terminalSessionId 连接 ID；空值忽略
     */
    public void invalidate(String terminalSessionId) {
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return;
        }
        cache.remove(terminalSessionId);
    }

    /** 清空全部缓存（测试/连接批量断开时可用）。 */
    public void invalidateAll() {
        cache.clear();
    }

    /** 缓存 TTL，测试可覆写以便不 sleep 3 秒。 */
    protected long cacheTtlMs() {
        return CACHE_TTL_MS;
    }

    /** 记录/清理单个连接缓存。 */
    public int cachedConnections() {
        return cache.size();
    }

    /** 安全执行采集命令——失败返回空串，不抛异常 */
    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String raw = sshTerminalService.execCommand(terminalSessionId, cmd);
            return raw != null ? raw.trim() : "";
        } catch (Exception e) {
            log.debug("环境采集失败: command={}, error={}", cmd, e.getMessage());
            return "";
        }
    }

    /** 一次采集结果的快照。 */
    private record CachedState(long at, Map<String, Object> state) {
    }
}

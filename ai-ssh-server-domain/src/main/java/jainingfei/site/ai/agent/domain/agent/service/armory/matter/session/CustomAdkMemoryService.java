package jainingfei.site.ai.agent.domain.agent.service.armory.matter.session;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;

/**
 * 自定义 ADK Memory 服务——空实现。
 *
 * <p>意图：收掉 ADK 的记忆职责边界，避免与业务侧记忆（MilestoneTracker /
 * ToolResultProvider / 最近命令）重复——同一份记忆在框架层和业务层各存一份，
 * 只会造成上下文打架。业务侧的记忆能力由 2-4/2-5 节的组件承担。
 */
@Component
public class CustomAdkMemoryService implements BaseMemoryService {

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.complete();
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.just(SearchMemoryResponse.builder()
                .memories(ImmutableList.of())
                .build());
    }
}

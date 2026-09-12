package jainingfei.site.ai.agent.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.SpringAiLlmAdapter;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ChatModelFactory;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ModelTuning;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.llm.provider.ProviderProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 规划器构建器——临时造一个"只会输出 JSON 计划"的智能体，跑一轮，把计划文本交给解析器（教程 2-10）。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>规划器没有任何工具</b>：它只负责"想"，不负责"干"——再聪明的规划器也碰不到服务器，
 *       计划的可执行性由后续的校验器与执行器保证</li>
 *   <li><b>纯内存会话 + 跑完即弃</b>：InMemory 三件套，不落库、不复用；它是一张草稿纸，
 *       不是需要追溯的正式会话</li>
 *   <li><b>模型走统一入口</b>：规划输出是结构化 JSON，推理强度取服务端默认（不额外加压），
 *       模型由 {@link ChatModelFactory} 提供，本类不出现任何厂商类型</li>
 * </ol>
 */
@Slf4j
@Service
public class PlannerAgentBuilder {

    /** 规划器超时（秒）：规划是"开跑前"的一步，不能让整个回合卡在这里 */
    private static final int PLANNER_TIMEOUT_SECONDS = 60;

    /** 规划器人格：只输出 JSON、限制规模、强调"变更任务必须依赖诊断任务"（幂等性与安全边界） */
    private static final String INSTRUCTION = """
            你是多Agent任务规划器。只输出一个JSON对象，不要输出Markdown或其他文字。
            JSON格式:
            {"tasks":[{"taskId":"唯一ID","agentName":"允许的子Agent名称","request":"完整任务指令","dependsOn":["依赖任务ID"],"timeoutSeconds":120}],"maxConcurrency":4}
            简单任务只输出一个任务。复杂任务最多5个并行诊断任务。变更任务必须依赖诊断任务。
            涉及写入/安装/重启等非幂等操作的任务，不要设置 maxRetries（默认 0），失败交由主智能体决策。
            只能选择用户提供的允许Agent，不要创建新的Agent。
            """;

    @Resource
    private ChatModelFactory chatModelFactory;

    /**
     * 跑一轮规划器，返回计划 JSON 文本。
     *
     * @param modelConfig    配置文件 chat-model（复用主链路同一通道与配置）
     * @param userRequest    用户原始任务描述
     * @param allowedAgents  允许派发的子智能体白名单（instruction 与 prompt 双重注入）
     * @return 规划器输出文本（可能裹 Markdown，由 {@link PlanParser} 抢救）
     * @throws IllegalStateException 规划器调用失败/超时
     */
    public String plan(AiAgentConfigTableVO.Module.ChatModel modelConfig, String userRequest,
                       List<String> allowedAgents) {
        String allowed = String.join(",", allowedAgents);
        // 规划器模型：同一通道、同一份配置，不额外加压（推理强度取服务端默认）
        ProviderProfile plannerProfile = chatModelFactory.create(modelConfig, ModelTuning.deterministic(null));

        BaseAgent planner = LlmAgent.builder()
                .name("DynamicTaskPlanner")
                .description("多 Agent 任务规划器：把用户任务拆成带依赖关系的子任务计划")
                .model(new SpringAiLlmAdapter(plannerProfile))
                .instruction(INSTRUCTION + "\n允许Agent:" + allowed)
                .build();

        Runner runner = Runner.builder()
                .app(com.google.adk.apps.App.builder()
                        // ADK 对 App 名做标识符校验（字母/数字/下划线），连字符会被拒：
                        // "Invalid app name ... must be a valid identifier"
                        .name("dynamic_planner")
                        .rootAgent(planner)
                        .plugins(List.of())
                        .build())
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .build();

        String prompt = "用户任务:" + userRequest + "\n允许Agent:" + allowed;
        long startedAt = System.currentTimeMillis();
        try {
            List<Event> events = runner.runAsync("planner-user", "planner-" + UUID.randomUUID(),
                            Content.fromParts(Part.fromText(prompt)),
                            RunConfig.builder().autoCreateSession(true).build())
                    .timeout(PLANNER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .toList()
                    .blockingGet();
            String planText = SubAgentResultExtractor.pickFinalText(events);
            log.info("规划器输出 | 耗时={}ms | 文本长度={} | allowedAgents:[{}]",
                    System.currentTimeMillis() - startedAt, planText.length(), allowed);
            return planText;
        } catch (Exception e) {
            log.error("规划器调用失败 | 耗时={}ms", System.currentTimeMillis() - startedAt, e);
            throw new IllegalStateException("planner failed", e);
        }
    }
}

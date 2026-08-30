package jainingfei.site.ai.agent.domain.agent.service.memory;

import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.IChatHistoryRepository;
import jainingfei.site.ai.agent.domain.agent.adapter.repository.ILongTermMemoryRepository;
import jainingfei.site.ai.agent.domain.agent.model.entity.ChatMessageEntity;
import jainingfei.site.ai.agent.domain.agent.model.entity.LongTermMemoryEntity;
import jainingfei.site.ai.agent.domain.agent.service.ILongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 长期记忆领域服务实现——吸收教程 2-8 节核心组件。
 *
 * <p>自动从对话流中提取 4 种结构化记忆，并支持关键词召回（粗筛+精排两阶段策略）。
 *
 * <p><b>写入侧：</b>消息流经时实时提取（规则式，不调 LLM），每条消息的提取入口由
 * {@code save*} 方法收口——先落库 chat_message，再做记忆提取。
 * <b>召回侧：</b>两阶段——粗筛从 DB 拉最近 120 条，精排在内存里做关键词重叠打分取 Top-N。
 *
 * <p>设计原则：零外部依赖（不需要 Embedding API、不需要向量数据库），
 * 纯 MySQL + 关键词匹配。对于 SSH 运维场景，关键词（nginx、redis、502、timeout）
 * 天然具有高区分度，召回精度够用；且"可配置任意模型"的约束下不假设模型方提供向量能力。
 */
@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** 记忆类型：用户偏好（最高价值，用户自己说的，置信度 0.85） */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";

    /** 记忆类型：环境事实（从工具输出检测 OS，置信度 0.70） */
    private static final String TYPE_ENVIRONMENT_FACT = "ENVIRONMENT_FACT";

    /** 记忆类型：软件版本（从工具输出检测版本号，置信度 0.78~0.82） */
    private static final String TYPE_SOFTWARE_FACT = "SOFTWARE_FACT";

    /** 记忆类型：排查案例（失败信号/助手结论，置信度 0.68~0.72） */
    private static final String TYPE_TROUBLESHOOTING_CASE = "TROUBLESHOOTING_CASE";

    /** 用户偏好检测关键词：消息中包含任一词即判定为可能的偏好表达 */
    private static final List<String> PREFERENCE_HINTS = List.of("以后", "默认", "记住", "优先", "不要", "请用", "习惯", "统一使用");

    /** 助手结论检测关键词：只提取含这些词的回复，避免把中间推理过程都存下来 */
    private static final List<String> CONCLUSION_HINTS = List.of("结论", "原因", "建议", "修复", "根因");

    /** 通用软件版本正则：匹配 "nginx version: 1.24.0"、"mysql Ver 8.0.42" 等格式 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)(nginx|redis|mysql|postgres|docker|java)[^\\n]{0,40}(version|ver|版本)[:= ]+([\\w.\\-]+)");

    /** Redis 专用版本正则：匹配 INFO 输出的 "redis_version:7.0.11" 键值格式 */
    private static final Pattern REDIS_VERSION_PATTERN = Pattern.compile("(?i)redis_version:([\\w.\\-]+)");

    /** 操作系统检测正则：匹配 ubuntu、centos、debian 等 OS 关键词 */
    private static final Pattern OS_PATTERN = Pattern.compile("(?i)(ubuntu|centos|debian|rocky|almalinux|fedora|linux|macos|darwin|windows)");

    /** 高信号失败特征：只有这些失败才值得记录为排查案例 */
    private static final List<String> FAILURE_SIGNALS = List.of(
            "permission denied", "connection refused", "no such file", "not found", "failed", "error");

    /** 中英文停用词：关键词提取时过滤，避免无意义词干扰匹配 */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(List.of(
            "请", "帮", "一下", "这个", "那个", "现在", "需要", "进行", "继续", "问题", "情况",
            "the", "and", "for", "with", "that", "this", "from", "into", "have", "has"));

    /** 粗筛候选集大小：从 DB 拉最近 N 条记忆进入精排 */
    private static final int COARSE_RECALL_LIMIT = 120;

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    /** 会话历史仓储（消息落库 + 冷启动恢复） */
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    // ==================== 记忆提取（record*） ====================

    @Override
    public void recordUserMessage(String userId, String sessionId, String message, String intentLabel) {
        if (isBlank(userId) || isBlank(message)) {
            return;
        }

        String normalized = normalize(message);
        boolean looksLikePreference = PREFERENCE_HINTS.stream().anyMatch(normalized::contains);
        if (!looksLikePreference) {
            return;
        }

        // memory_key 用 sha1("pref:"+内容)：用户三次说"以后加 sudo"三次哈希相同，
        // 走 saveOrUpdate 更新 hit_count，同一偏好不重复占行
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_USER_PREFERENCE)
                .memoryKey(sha1("pref:" + normalized))
                .content(truncate(message, 300))
                .keywords(joinKeywords(extractKeywords(message + " " + nullSafe(intentLabel))))
                .sourceRole("user")
                .confidence(0.85)
                .hitCount(1)
                .build());
    }

    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success) {
        if (isBlank(userId) || isBlank(resultContent)) {
            return;
        }

        String text = truncate(resultContent, 500);

        // 1. 操作系统检测
        Matcher osMatcher = OS_PATTERN.matcher(text);
        if (osMatcher.find()) {
            String os = osMatcher.group(1).toLowerCase(Locale.ROOT);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_ENVIRONMENT_FACT)
                    .memoryKey("os:" + os)
                    .content("目标环境可能为 " + os)
                    .keywords(joinKeywords(extractKeywords(os + " environment system os")))
                    .sourceRole("tool")
                    .confidence(0.70)
                    .hitCount(1)
                    .build());
        }

        // 2. 软件版本检测（通用正则）
        Matcher versionMatcher = VERSION_PATTERN.matcher(text);
        if (versionMatcher.find()) {
            String software = versionMatcher.group(1).toLowerCase(Locale.ROOT);
            String version = versionMatcher.group(3);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey(software + ":version")
                    .content(software + " 版本为 " + version)
                    .keywords(joinKeywords(extractKeywords(software + " version " + version)))
                    .sourceRole("tool")
                    .confidence(0.78)
                    .hitCount(1)
                    .build());
        }

        // 3. Redis 专用版本检测：INFO 输出是 redis_version:7.0.11 键值格式，与 nginx 的
        //    "nginx version:" 空格分隔格式不同，通用正则的版本捕获组在此可能截到错误片段
        Matcher redisVersionMatcher = REDIS_VERSION_PATTERN.matcher(text);
        if (redisVersionMatcher.find()) {
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey("redis:version")
                    .content("redis 版本为 " + redisVersionMatcher.group(1))
                    .keywords(joinKeywords(extractKeywords("redis version " + redisVersionMatcher.group(1))))
                    .sourceRole("tool")
                    .confidence(0.82)
                    .hitCount(1)
                    .build());
        }

        // 4. 失败信号记录（仅工具执行失败且含高信号特征时）
        if (!success && looksLikeHighSignalFailure(text)) {
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_TROUBLESHOOTING_CASE)
                    .memoryKey(sha1("failure:" + normalize(text)))
                    .content("历史失败信号: " + text)
                    .keywords(joinKeywords(extractKeywords(text + " " + nullSafe(toolName))))
                    .sourceRole("tool")
                    .confidence(0.68)
                    .hitCount(1)
                    .build());
        }
    }

    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent) {
        if (isBlank(userId) || isBlank(assistantContent)) {
            return;
        }

        String normalized = normalize(assistantContent);
        boolean looksLikeConclusion = CONCLUSION_HINTS.stream().anyMatch(normalized::contains);
        if (!looksLikeConclusion) {
            return;
        }

        String summary = truncate(assistantContent, 400);
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_TROUBLESHOOTING_CASE)
                .memoryKey(sha1("assistant:" + normalize(summary)))
                .content(summary)
                .keywords(joinKeywords(extractKeywords(summary)))
                .sourceRole("assistant")
                .confidence(0.72)
                .hitCount(1)
                .build());
    }

    // ==================== case 层统一入口（save*，落库 + 提取闭环） ====================

    @Override
    public void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound) {
        if (firstRound && !isBlank(sessionId) && !isBlank(message)) {
            try {
                chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(message)
                        .priority("MEDIUM")
                        .tokenCount(message.length() / 2)
                        .build());
            } catch (Exception e) {
                log.warn("保存用户消息落库失败 sessionId={}", sessionId, e);
            }
        }
        recordUserMessage(userId, sessionId, message, intentLabel);
    }

    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent) {
        if (isBlank(assistantContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(assistantContent)
                    .priority("MEDIUM")
                    .tokenCount(assistantContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存助手消息落库失败 sessionId={}", sessionId, e);
        }
        recordAssistantConclusion(userId, sessionId, assistantContent);
    }

    @Override
    public void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success) {
        if (isBlank(resultContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .priority("HIGH")
                    .tokenCount(resultContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存工具消息落库失败 sessionId={}, tool={}", sessionId, toolName, e);
        }
        recordToolObservation(userId, sessionId, toolName, resultContent, success);
    }

    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        if (isBlank(sessionId)) {
            return List.of();
        }
        try {
            return chatHistoryRepository.getRecentMessages(sessionId, limit);
        } catch (Exception e) {
            log.warn("查询最近消息失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    // ==================== 召回（粗筛 + 精排） ====================

    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }

        // 阶段一：粗筛——从 DB 拉最近 N 条记忆（用户维度，更新时间倒序）
        List<LongTermMemoryEntity> candidates = longTermMemoryRepository.queryRecentByUserId(userId, COARSE_RECALL_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 阶段二：精排——关键词重叠打分，过滤 score>0，按分数降序 + 更新时间降序取 Top-N
        Set<String> queryKeywords = extractKeywords(query);
        return candidates.stream()
                .map(memory -> new MemoryScore(memory, score(memory, queryKeywords)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(MemoryScore::score).reversed()
                        .thenComparing(ms -> ms.memory().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit > 0 ? limit : 5)
                .map(MemoryScore::memory)
                .collect(Collectors.toList());
    }

    @Override
    public String buildMemorySummary(String userId, String query, int limit) {
        List<LongTermMemoryEntity> memories = queryRelevantMemories(userId, query, limit);
        if (memories.isEmpty()) {
            return "";
        }
        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + truncate(memory.getContent(), 160))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public boolean isFailureContent(String content) {
        return looksLikeHighSignalFailure(content);
    }

    // ==================== 私有工具方法 ====================

    /** 保存长期记忆——try-catch 旁路写入，失败不影响主流程 */
    private void saveMemory(LongTermMemoryEntity memoryEntity) {
        try {
            longTermMemoryRepository.saveOrUpdate(memoryEntity);
        } catch (Exception e) {
            log.warn("保存长期记忆失败 type={}, key={}", memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(), e);
        }
    }

    /**
     * 打分公式：关键词重叠数 × 2 + USER_PREFERENCE 加权（+1）。
     * 偏好额外加分因为它是全局适用的——无论用户问什么，"以后加 sudo"都应被召回。
     * 无查询关键词时仅 USER_PREFERENCE 返回 1 分。
     */
    private int score(LongTermMemoryEntity memory, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) {
            return TYPE_USER_PREFERENCE.equals(memory.getMemoryType()) ? 1 : 0;
        }

        Set<String> memoryKeywords = extractKeywords(nullSafe(memory.getKeywords()) + " " + nullSafe(memory.getContent()));
        int overlap = 0;
        for (String keyword : queryKeywords) {
            if (memoryKeywords.contains(keyword)) {
                overlap += 2;
            }
        }
        if (TYPE_USER_PREFERENCE.equals(memory.getMemoryType())) {
            overlap += 1;
        }
        return overlap;
    }

    /**
     * 关键词提取：分割（保留英文/数字/下划线/中文）→ 过滤（长度<2、停用词）→ 去重。
     * 写入时（记忆 keywords 字段）与查询时（召回查询）共用同一提取逻辑，天然对称。
     */
    private Set<String> extractKeywords(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        String normalized = normalize(text);
        String[] parts = normalized.split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        Set<String> keywords = new LinkedHashSet<>();
        Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(part -> !STOP_WORDS.contains(part))
                .forEach(keywords::add);
        return keywords;
    }

    /** 关键词集合拼接为逗号分隔字符串，存入 keywords 字段 */
    private String joinKeywords(Set<String> keywords) {
        return String.join(",", keywords);
    }

    /** 文本归一化：转小写 + 换行替换为空格 + trim */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
    }

    /** 截断文本，超长部分用 "..." 表示 */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 高信号失败特征判断——只有这些失败才值得记录为排查案例 */
    private boolean looksLikeHighSignalFailure(String text) {
        String normalized = normalize(text);
        return FAILURE_SIGNALS.stream().anyMatch(normalized::contains);
    }

    /** SHA-1 哈希生成 memory_key 去重键；失败时降级为 hashCode，保证不抛异常 */
    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** 记忆打分内部记录类，用于排序 */
    private record MemoryScore(LongTermMemoryEntity memory, int score) {}
}

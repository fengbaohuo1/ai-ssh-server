# ai-ssh-server 项目说明

## 项目概述

AI + SSH 服务端：SSH 连接管理、远程文件、操作审计、AI 运维助手。DDD 分层，包名 `jainingfei.site.ai.agent`。

- **Spring Boot 4.1.0** + **MyBatis-Plus 3.5.16** + MySQL 8 / H2 双模式
- 模块：api / domain / infrastructure / trigger / case / types / app
- 领域：`ssh`（连接/终端）、`filesystem`（SFTP）、`security`（凭证/审计）、`agent`（ADK 装配）

## 架构速览

- AI 回合：`case` 层节点链 Root → AiCall → ToolCall → LoopDecision → UserFeedback；循环在 ADK Runner 内部。
- 智能体装配：`app` 层 `AiAgentAutoConfig` 读 `agent.yml` + `llm.yml` → `AgentNode` 二段式装配（leaf 工具集 → 派发工具集），`registerBean` 覆盖式注册、`CustomAdkSessionService` 共享 ADK 会话；配置保存后发 `AgentConfigChangedEvent` 热重装。
- SSH：`UnifiedSessionManager`（ChannelShell 人机共用终端 / ChannelExec 供 AI 结构化执行）；`SshExecuteAdkTool` 为 prototype bean，按 agent 注入 `readOnly`。
- 存储：local=H2（`db/schema-h2.sql` 幂等建表），server=MySQL（脚本在 `docs/dev-ops/mysql/sql`）；`AiSshHome` 在 `Application.main()` 中先于 Spring 创建 `${AI_SSH_HOME}/{data,config,logs}`（`@SpringBootTest` 不经过 main，测试用 `src/test/resources/application-local.yml`）。

## 关键约束（改代码前必看）

- 配置写入顺序：**校验 → ensure → 备份 → 原子写**；校验失败不落盘、不产生 `.bak`；热重装失败保留旧装配。
- 新增派发工具或会话入口必须同步 `DispatchBudget.beginTurn`；工具语义变化必须同步 `AgentServiceController#toolsFor` 与客户端角色徽标。
- 新增写操作正则必须用 `CommandGuard.CMD_START` 前缀（`DANGEROUS_PATTERN` 除外，保持宽松）。
- `read-only` agent 默认不挂载 MCP，确认只读安全才加 `mcp-readonly-safe: true`。
- 新增发 `tool_call` 的路径必须同时 `DynamicContext.markStepStarted(id, name)`，否则回合收口补发看不到。
- `DynamicContext.currentCallText` 只是单次 LLM 调用的文本累积器，不能当回合累积器。
- 新增异常分支不要假设 `getMessage()` 非空（`AppException` 已改为 `super(message)`）。
- `threadPoolExecutor` 线程数必须 ≥ 编排器并发，否则提交前 acquire 会卡死。

## 常用命令

```bash
mvn -B -pl ai-ssh-server-app -am -DskipTests compile        # 编译
mvn -B -pl ai-ssh-server-app -am -DskipTests package        # 打包（先停占用 jar 的后端进程）

# 定向单测（纯 JVM，环境无关）
mvn -pl ai-ssh-server-app -am \
  "-Dtest=CommandGuardTest,CommandOutcomeAnalyzerTest,DispatchBudgetTest,AgentConfigValidatorTest,SubAgentResultExtractorTest,TerminalStateProviderTest,TaskStateMachineTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- 起服务：`java -jar ai-ssh-server-app/target/ai-ssh-server-app.jar --spring.profiles.active=local --server.port=8091`；验证用临时 `AI_SSH_HOME` + 空闲端口，避免污染真实数据。
- 依赖真实 LLM Key / SSH 服务器的集成测试失败属正常；`SshSessionPortManualTest` 需 `AI_SSH_MANUAL_SSH=true` + 控制台输入。

## 配置与数据

- `${AI_SSH_HOME}`（默认 `~/.ai-ssh`）：`data/`（H2）、`config/agent.yml` + `llm.yml`、`logs/`。
- `agent.yml`：`chat-model`（provider/model/api-key…）、`tool-mcp-list`、`agents`（`sub-agents` / `delegate-mode` / `read-only` / `instruction`）、`runner.agent-name`；`llm.yml` 只是模型覆盖层，不重写 agent.yml。
- 关键配置：`ai.ssh.exec.timeout-seconds=300`、`max-output-bytes=262144`、`ai.security.*`。
- 环境变量：`AI_SSH_HOME`、`AI_SSH_PROFILE`（local/server）、`AI_SSH_DB_URL/USERNAME/PASSWORD`、`AI_SSH_LLM_API_KEY`、`AI_SECURITY_CREDENTIAL_KEY/API_TOKEN`、`AI_SSH_AUTH_ENABLED`、`SERVER_PORT`。

## 版本要点（细节见下方文档库）

- **v2**：凭证 AES-256-GCM、SFTP 文件系统与路径闸门、操作审计、接口令牌鉴权、`read-only` 工具边界。
- **v3**：桌面 sidecar（jar + jlink JRE）、H2/MySQL 双模式、配置热重装。
- **v3.2**：`delegate-mode`（hybrid 默认 / dispatch-only）、只读智能体命令级拦截、`SshCommandResult` 结构化执行（stderr/退出码/超时/输出上限）。
- **v3.3**：派发预算、会话绑定自愈、配置前置校验、MCP 只读边界与回收、编排器健壮性、审计上下文传播、ReAct 可观测与可取消、悬挂工具步兜底、执行痕迹、`AppException` 带 message。

## 约定

- 命名：`XxxEntity` / `XxxVO`（domain）、`XxxPO`（infrastructure）、`IXxxDao`、`IXxxRepository`、`IXxxPort`、`IXxxApiService`。
- 统一返回 `Response<T>`（code/info/data），错误码用 `ResponseCode`。
- MySQL 8，MyBatis-Plus 逻辑删除字段 `deleted`（0/1）；SSH 用 JSch 0.1.55，端口 `ISshSessionPort`。
- DDD 边界：domain 只定义端口/领域服务，不依赖 framework 实现。

## 打包体积（2026-10 实测；结论：300 MB 可接受，暂不优化）

- 组成：jar 280 MB（348 个 lib）+ jlink JRE 77.5 MB + exe 8.9 MB → NSIS 300 MB；安装包≈各部件之和。
- 大头是 ADK 带入但未用的厂商/云集成（Vertex protos 37、gRPC 23、google-cloud 27、anthropic+kotlin 14.5、eclipse/arrow 16.7、docker+BouncyCastle 10.8、adk-dev 12 MB）；`openai-java` 50 MB 是 `spring-ai-openai` 真需求，`google-genai` 是 ADK 事件类型必需。
- 以后要瘦身（已实测）：根 `pom.xml` 给 `google-adk` 加 exclusions（保留 `google-genai`、`grpc-netty-shaded`），jar 280→145 MB；adk-dev/sample/langchain4j 降 `test`，lombok 改 `provided`；客户端侧见 `ai-ssh-client/AGENTS.md`。

## 文档库

- 学习总结 / 设计讨论 / 踩坑：`D:\docs\knowledge\ai-ssh\`，遵循 `D:\docs\AGENTS.md`（一篇一主题、问题背景→核心方案→关键代码→踩坑、互相引用、Mermaid）。
- v3.1–v3.3 执行记录：`D:\docs\projects\ai-ssh\`（`type: facts`，含 `verified_at`）。
- 已有主题：SSH 库表/DDD 分层/人机共用终端、ReAct Case 架构、动态上下文增强、上下文治理与压缩、意图识别、任务步进状态机、长期记忆、缓存与推理强度、子 Agent 派发、厂商适配、SSE 步骤协议 v2、托管循环回归、空参数函数调用、SSE 换行碎片、终端方案对比。

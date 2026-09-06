# ai-ssh-server 项目说明

## 项目概述

AI + SSH 的服务端项目，提供 SSH 连接的管理和操作能力。基于 DDD 四层架构设计。

- **Spring Boot 4.1.0** + **MyBatis-Plus 3.5.16** + **MySQL 8.0**
- 包名：`jainingfei.site.ai.agent`
- 模块：api / domain / infrastructure / trigger / types / app / case

## 总结存放要求

所有关于 ai-ssh-server 的学习总结、设计讨论、踩坑记录，统一存放在：

```
D:\study\notes\ai-ssh\
```

以便集中查阅和管理。

**书写总结时必须遵循** `D:\study\notes\CLAUDE.md` 中的编写规则——一篇一主题、模板结构（问题背景→核心方案→关键代码→踩坑记录）、互相引用、善用 Mermaid 图表。

## SSH 领域设计

SSH 连接框架用于统一管理 SSH 连接的生命周期：创建配置、建立连接、断开连接、查询列表、软删除。

详细总结见 `D:\study\notes\ai-ssh\`：
- [SSH 库表设计取舍.md](D:\study\notes\ai-ssh\SSH%20库表设计取舍.md) — 表拆分依据（访问频率×数据体积）、VO vs Entity 判定、MP 版本踩坑
- [SSH 领域 DDD 分层落地.md](D:\study\notes\ai-ssh\SSH%20领域%20DDD%20分层落地.md) — 聚合设计、仓库/端口接口隔离、调用链路、踩坑记录
- [人机共用终端——统一会话层设计与实现.md](D:\study\notes\ai-ssh\人机共用终端——统一会话层设计与实现.md) — WebSocket 和 AI 工具合并为一条终端通路，Marker 输出检测，写入锁

## AI Agent 踩坑记录

详细总结见 `D:\study\notes\ai-ssh\`：
- [空参数函数调用问题排查与修复.md](D:\study\notes\ai-ssh\空参数函数调用问题排查与修复.md) — 4 轮排查（来源确认→工具防御→架构改动→根因），最终定位 `FixedSpringAI` 中 `Map.toString()` 产生非法 JSON
- [SSE 流式换行碎片问题排查与修复.md](D:\study\notes\ai-ssh\SSE流式换行碎片问题排查与修复.md) — 5 轮排查（ADK→SpringAI→后端过滤→前端渲染→优化），Spring AI 流式 meta-event，前端 `norm()` 密度规整缓解
- [终端方案对比——ChannelShell vs ChannelExec](D:\study\notes\ai-ssh\终端方案对比——ChannelShell%20vs%20ChannelExec.md) — 三种方案演变（marker → 双缓冲+prompt → ChannelExec），教学项目参考
- [ReAct Case 架构设计与实现](D:\study\notes\ai-ssh\ReAct%20Case%20架构设计与实现.md) — 教学项目 2-3 节落地：节点链路编排、DynamicContext、外部循环 vs ADK 内部循环、5 个 bug 从发现到修复
- [动态 Prompt 上下文增强——AI 装上眼睛与记性](D:\study\notes\ai-ssh\动态%20Prompt%20上下文增强——AI%20装上眼睛与记性.md) — 教学项目 2-4 节落地：环境采集 + 里程碑追踪 + 最近命令记忆 + 危险命令拦截
- [显式 ReAct 编排——循环控制权回归应用层](D:\study\notes\ai-ssh\显式%20ReAct%20编排——循环控制权回归应用层.md) — 架空 ADK 内部循环：FixedSpringAI 单轮模式（工具调用转 `<tool_calls>` 标签）、每轮富化落地、多命令结果累积回灌、5 个 bug 修复
- [ReAct 上下文治理与历史净化——教程 2-5 和 2-6 吸收落地](D:\study\notes\ai-ssh\ReAct%20上下文治理与历史净化——教程%202-5%20和%202-6%20吸收落地.md) — 吸收教程 2-5/2-6：ContextProvider 体系（任务锚点 + 工具摘要）、HybridReducer 历史裁剪三件套、CustomAdkSessionService 框架层净化 + 限流、四道闸门分工
- [意图识别——规则与LLM级联+反馈回路（教程 2-7 吸收落地）](D:\study\notes\ai-ssh\意图识别——规则与LLM级联+反馈回路（教程%202-7%20吸收落地）.md) — 吸收教程 2-7：规则→LLM 级联置信度漏斗、反馈回路重分类、ContextTracker/LRU 缓存、显式循环适配（第 1 轮识别、ToolCallNode 反馈、修正提示一次性注入）、spring-ai 2.x 适配
- [任务步进状态机——托管循环回合收口落地](D:\study\notes\ai-ssh\任务步进状态机——托管循环回合收口落地.md) — V3.2：TaskStateVO 三入口写路径（AiCallNode 分类后 sync / ToolCallNode 每工具打点 / LoopDecisionNode 回合收口）、completed 判据收敛到回合收口（教学版"首次成功即完成"在托管循环下是 bug）、CONTINUE 会话意图回填、任务态暂存内存缓存
- [前端配置信息真实化——后端配置文件只读回传与 UI 对齐](D:\study\notes\ai-ssh\前端配置信息真实化——后端配置文件只读回传与%20UI%20对齐.md) — query_ai_agent_config_list 回传配置文件真实信息（chatModel 含 context-size、subAgents、工作流、MCP 工具归一、skills、plugins，剔除 apiKey）；客户端状态指示灯替换连接开关、输入栏假模型占位清理、AI 配置侧栏只读展示
- [长期记忆与对话持久化——教程 2-8 吸收落地](D:\study\notes\ai-ssh\长期记忆与对话持久化——教程%202-8%20吸收落地.md) — 吸收教程 2-8：4 张表（chat_session/chat_message/chat_milestone/long_term_memory）、消息流经时规则提取 4 类记忆、UNIQUE 键去重 + hit_count、粗筛精排召回、节点挂钩旁路写入、冷启动恢复与会话连续性、顺带修复 3 个 bug（工具摘要注入缺席/回灌累积上限/会话连续性）
- [上下文策略的代际演进与场景适配——设计讨论复盘](D:\study\notes\ai-ssh\上下文策略的代际演进与场景适配——设计讨论复盘.md) — 2-8 前后设计讨论沉淀：记忆价值=重观察代价、轨迹 vs 里程碑分工、正则长尾实测（Redis 正则与 9 种版本输出）、order=25 隐性契约、每轮裁剪 vs 阈值压缩、SaaS vs 本地 Agent 保守分层；附项目待优化清单
- [缓存命中与推理强度——教程 2-9 吸收落地](D:\study\notes\ai-ssh\缓存命中与推理强度——教程%202-9%20吸收落地.md) — 吸收教程 2-9：reasoning-effort 配置链路、streamUsage + usage/缓存命中观测（spring-ai 2.0.0 适配）、动态上下文前缀→后缀全套翻转（净化落库字节不变论证）、会话治理参数化、工具输出 head/tail 保留
- [托管循环回归与上下文预算化压缩——架构反思落地](D:\study\notes\ai-ssh\托管循环回归与上下文预算化压缩——架构反思落地.md) — 撤销显式 ReAct 循环（V3）：ADK Runner 内部循环回归（FixedSpringAI partial/聚合语义、聚合事件一条一落库）、案例层按教学项目恢复**节点链路**（Root→AiCall→ToolCall→LoopDecision→UserFeedback 回合流水线，无循环回跳；AiCall 事件中继、ToolCall 回合后置工具治理、UserFeedback 收口落库）、上下文长度管理升级落在 ADK 1.9 原生事件压缩（EventsCompactionConfig + 确定性摘要器 P4.1）、回归驱动修复三连（tool 消息配对 400 / text must not be null / 聚合去重 / part 结构化截断）
- [上下文长度管理升级——预算化、工具输出治理与 compact 引擎设计稿](D:\study\notes\ai-ssh\上下文长度管理升级——预算化、工具输出治理与%20compact%20引擎设计稿.md) — 主流 compact 对照（dsh 本机源码实证：thresholdRatio 0.8/retainRatio 0.16/checkpoint/tool-pairing）、W2 参数化与 W3 head/tail 已落地、W4 compact 引擎设计稿（预算触发、折叠回填、切割点平衡、写回机制）——已按 ADK 原生事件压缩落地（P4.1）
- 相关架构改动：删除手动历史嵌入、人机终端面板显示最终去掉、显式 ReAct "每轮循环跳转"语义删除（V3 托管循环回归，代码见 git 历史与《托管循环回归与上下文预算化压缩》）；案例层以教学式节点链路（回合流水线）重建（V3.1）

### 核心 API

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/v1/ssh/connection/create` | 创建连接配置 |
| POST | `/api/v1/ssh/connection/{id}/connect` | 建立连接 |
| POST | `/api/v1/ssh/connection/{id}/disconnect` | 断开连接 |
| GET | `/api/v1/ssh/connection/list?userId=` | 查询连接列表 |
| GET | `/api/v1/ssh/connection/{id}` | 查询连接详情 |
| DELETE | `/api/v1/ssh/connection/{id}` | 删除连接 |

### 领域模型

```
SshConnectionAggregate
  ├── SshConnectionEntity        ← 基础连接信息 + 凭证 + 状态
  └── SshConnectionConfigEntity  ← 高级配置（独立表）
```

### 项目约定

- 实体后缀：`XxxEntity`（domain 层）
- 值对象后缀：`XxxVO`（domain 层）
- 持久化对象后缀：`XxxPO`（infrastructure 层，MP 注解）
- DAO 接口后缀：`IXxxDao`，继承 `BaseMapper<XxxPO>`
- 仓库接口：领域层定义（`IXxxRepository`），基础设施层实现
- 端口接口：领域层定义（`IXxxPort`），基础设施层实现
- API 接口：api 模块定义（`IXxxApiService`），trigger 模块实现
- 所有接口统一返回 `Response<T>`（code / info / data）
- 错误码使用 `ResponseCode` 枚举

### 数据库

```
MySQL 8.0 — 106.54.3.66:13306 / ai_ssh
MyBatis-Plus 逻辑删除字段: deleted (0-未删, 1-已删)
```

### SSH 客户端

- 库：JSch 0.1.55
- 端口：`ISshSessionPort`（domain 层）→ `SshSessionPort`（infrastructure 层）
- 连接管理：`ConcurrentHashMap<String, Session>` 会话池

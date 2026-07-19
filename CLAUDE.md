# ai-ssh-server 项目说明

## 项目概述

AI + SSH 的服务端项目，提供 SSH 连接的管理和操作能力。基于 DDD 四层架构设计。

- **Spring Boot 4.1.0** + **MyBatis-Plus 3.5.16** + **MySQL 8.0**
- 包名：`jainingfei.site.ai.agent`
- 模块：api / domain / infrastructure / trigger / types / app

## 总结存放要求

所有关于 ai-ssh-server 的学习总结、设计讨论、踩坑记录，统一存放在：

```
D:\study\notes\ai-ssh\
```

以便集中查阅和管理。项目根目录下的 `CLAUDE.md` 不存放总结正文，只保留项目说明和指向总结文档的链接。

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
- 相关架构改动：删除手动历史嵌入、人机终端面板显示最终去掉

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

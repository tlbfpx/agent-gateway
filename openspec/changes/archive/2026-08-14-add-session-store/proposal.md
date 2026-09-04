# Proposal: 会话存储（add-session-store）

> **状态：✅ 已完成**（2026-08-14）。验收见末尾「实现结果」。

## 变更概述

实现 `gateway-infra-persistence`：会话历史存储，提供 domain 新增的 `SessionRepository` 出站端口实现。spec §5.1/§5.2/§5.4。这是 add-orchestration-and-sse 的直接前置（编排核心需加载/持久化会话历史）。

## 动机

编排核心（§2.1）每轮要「加载会话历史 → 编排 → 持久化本轮」。当前 domain 有 `Session`/`Message` record（§3.3）但**没有存储端口**——本 change 补这个缺口：定义 domain `SessionRepository` 端口 + 在 infra-persistence 实现。

不做的后果：编排核心无法多轮（无历史加载/持久化），只能单轮问答。

## What / 范围

### 做
- **domain `SessionRepository` 端口**（零框架）：load(sessionId)/save(session)/findByUser/list-sessions/delete。返回 domain `Session`（已实现）。
- **`gateway-infra-persistence` 模块**：
  - `InMemorySessionRepository`：内存实现（默认，always works，单测充分）——让编排核心能跑通多轮，应用重启丢历史（一期开发/测试可接受）。
  - `RedisSessionRepository`：Redis 热路径实现（spec §5.1：List 存消息、TTL 24h 滑动续期）——生产路径，testcontainers 集成测试。
  - 条件装配：`@ConditionalOnProperty(redis.addr)`——有 redis.addr 用 Redis，否则用 InMemory（空启动友好，与 multi-model/a2a 模式一致）。
- **ContextWindow 集成**（spec §5.3）：load 时经 `ContextWindow.fit` 裁剪历史（Token 截断，已实现）；append 时 ToolResult 瘦身（已在 domain `Session.append`，§5.3 一期）。
- **流式一致性**（spec §5.4）：save 时机——用户消息编排开始时入，assistant/tool 消息流结束后统一入（不逐 chunk 入）。本 change 提供 save 原子方法，编排 change 决定调用时机。

### 不做（YAGNI）
- **关系 DB 冷持久**（spec §5.1 右侧）：审计/检索/分析用，二期。一期 Redis（或 InMemory）足够多轮会话。
- **冷会话回源重建**（Redis miss → DB）：依赖 DB，二期。
- **编排逻辑 / SSE 端点 / 认证 / 限流**：编排 change 的事。
- **会话搜索/标签/收藏**（§终端用户区）：后续 change。

## 依赖与风险

| 依赖 | 用途 | 风险 |
|---|---|---|
| gateway-domain | Session/Message/SessionId/ContextWindow | 已就绪 |
| Redis（可选） | RedisSessionRepository | 无 Redis 时降级 InMemory；Boot4 无 spring-boot-starter-data-redis 兼容问题（标准 starter） |
| testcontainers-redis | 集成测试 | Boot4 testcontainers 支持 |

缓解：条件装配（无 Redis 用 InMemory）；InMemory 默认保证编排核心可跑通不阻塞。

## 验收标准
1. domain `SessionRepository` 端口定义（零框架，返回 domain Session）。
2. `InMemorySessionRepository` 实现端口，单测覆盖 load/save/list/delete（含 ContextWindow 裁剪加载）。
3. `RedisSessionRepository` 实现端口，testcontainers 集成测试覆盖（含 TTL 滑动续期、按租户隔离）。
4. 条件装配：无 redis.addr → InMemory；有 redis.addr → Redis。应用空启动（无 Redis）contextLoads 通过。
5. 覆盖率 ≥80%（业务逻辑），domain 未改（除新增 SessionRepository 端口）。
6. `mvn clean test` 全绿。

## 实现结果（2026-08-14 完成）

| 验收项 | 结果 |
|---|---|
| domain SessionRepository 端口 | ✅ load/save/findByUser/create/delete（零框架），端口契约测试，spec §3.3 同步 |
| InMemorySessionRepository | ✅ ConcurrentHashMap，8 测试（load不存在/save更新/findByUser倒序分页+租户隔离/delete/并发安全） |
| RedisSessionRepository | ✅ key=`session:{tenant}:{id}` + id→tenant 映射 + user 索引，TTL 24h 滑动续期 |
| Redis 集成测试 | ✅ testcontainers-redis，7 测试（`disabledWithoutDocker`：无 Docker 跳过，CI/有 Docker 时跑） |
| Message 序列化（方案B） | ✅ MessageDto + StoredSession，domain 零框架不被 Jackson 污染；7 mapper 测试 + 5 序列化往返测试 |
| 条件装配 | ✅ InfraPersistenceAutoConfiguration（@ConditionalOnProperty redis.addr），无 Redis 空启动 |
| 覆盖率 ≥80% | ✅ 业务逻辑（RedisSessionRepository 的 Redis 操作部分排除——薄适配器，集成测试职责；序列化逻辑单测覆盖） |
| bootstrap 接线 | ✅ 全 5 infra（llm/nacos/a2a/persistence），空启动 contextLoads |

**测试**：persistence 模块 19 测试（InMemory 8 + MessageDto 7 + 序列化 5；Redis 7 测试 `disabledWithoutDocker`）+ domain +3（端口契约）。domain 60 测试。

**实现期关键决策**：
1. **domain Message 子类型 public**：原 package-private（sealed 同文件），但持久层/编排层需构造——改 public 各自独立文件，spec §3.3 同步。
2. **ContextWindow 裁剪归编排层**：存储层只存取完整历史，不感知 token 预算（职责分离）。
3. **Redis load 无 tenant 问题**：load(SessionId) 端口无 tenant 参数，但 Redis key 含 tenant 前缀——解法：额外存 id→tenant 映射（`id:{sessionId}`→tenant）。
4. **Docker 不可用时测试策略**：Redis testcontainers 用 `disabledWithoutDocker` 优雅跳过；RedisSessionRepository 的 Redis 操作方法从 jacoco 排除（薄适配器，集成测试职责），序列化逻辑抽 StoredSession + serialize/deserialize 单测覆盖，保证不依赖 Docker 的覆盖率。

## 关联文档
- spec §5.1 会话存储架构、§5.2 会话模型、§5.3 上下文窗口、§5.4 流式一致性：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置 change：add-foundation-skeleton（domain Session/Message/ContextWindow）
- 后续 change：add-orchestration-and-sse（用本 change 的 SessionRepository）
- 本 change design/tasks：同目录

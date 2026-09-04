# Tasks: 会话存储（add-session-store）

> **任务清单视图**。详细 step 待 writing-plans。遵循 AGENTS.md：domain 端口与 infra 实现可并行起草但 domain 先定稿。

## Chunk 1: domain SessionRepository 端口
- [ ] **Task 1: domain SessionRepository 端口 + 测试契约**
  - 目标：在 `domain/orchestration/`（或 `domain/session/`）新增 `SessionRepository` 接口（load/save/findByUser/create/delete），零框架。
  - 完成判据：接口定义 + 端口契约测试（匿名实现验证签名可被实现）；domain 全测试绿 + jacoco ≥90% 不回退；spec §3.3 同步加 SessionRepository。

## Chunk 2: infra-persistence InMemory 实现
- [ ] **Task 2: 模块骨架 + InMemorySessionRepository**
  - 目标：建 gateway-infra-persistence 模块（pom 依赖 domain）；InMemorySessionRepository（ConcurrentHashMap，create/load/save/findByUser/delete）。
  - 完成判据：实现端口；单测覆盖 load 不存在返 null / save 后 load 一致 / findByUser 分页+租户隔离 / delete；mvn verify jacoco ≥80%。
- [ ] **Task 3: ContextWindow 集成测试**
  - 目标：验证编排层用法——load 全量历史 → ContextWindow.fit 裁剪（不在此实现 fit，已在 domain；本 task 只测"load 返回完整历史 + fit 后条数正确"的集成）。
  - 完成判据：集成测试（InMemory + ContextWindow）覆盖裁剪场景。

## Chunk 3: Redis 实现 + 条件装配
- [ ] **Task 4: Message 序列化（infra DTO mapper）**
  - 目标：domain Message ↔ infra DTO 双向 mapper（方案 B，domain 零框架），Jackson 序列化。
  - 完成判据：mapper 单测覆盖 4 种 Message 子类型往返；DTO 序列化/反序列化一致。
- [ ] **Task 5: RedisSessionRepository**
  - 目标：Redis 实现（key=`session:{tenant}:{id}`，List/JSON 存消息，TTL 24h 滑动续期，user Set 索引）。
  - 完成判据：实现端口；testcontainers-redis 集成测试覆盖 load/save（含 TTL 续期）/findByUser（user 索引）/delete/租户隔离。
- [ ] **Task 6: 条件装配（InfraPersistenceAutoConfiguration）**
  - 目标：@ConditionalOnProperty(redis.addr)——有 redis 用 RedisSessionRepository，无用 InMemory。
  - 完成判据：bootstrap 接线 infra-persistence，无 redis.addr 空启动 contextLoads；有 redis.addr（testcontainers）装配 Redis。

## Chunk 4: 验证 + 归档
- [ ] **Task 7: 全量验证 + 依赖方向 + 归档**
  - 判据：mvn clean verify 全绿（含 testcontainers-redis）；依赖方向负向断言（infra-persistence 只依赖 domain）；proposal 标 ✅ + 实现结果。

## 依赖与并行性
```
Task 1 (domain 端口) 定稿
  ↓
Task 2/3 (InMemory) ─┐
Task 4 (Message mapper) ─┤（Task 2/3/4 可并行，依赖 Task 1）
                       ↓
Task 5 (Redis，依赖 Task 4)
  ↓
Task 6 (条件装配，依赖 2/5)
  ↓
Task 7 (验证 + 归档)
```

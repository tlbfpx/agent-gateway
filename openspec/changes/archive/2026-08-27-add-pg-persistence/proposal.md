# Proposal: PG 持久化补全（add-pg-persistence）

> **状态**：阶段二自评 Approved（主线直写，小 scope）
> **来源**：用户指令「未配置持久化存储，完成所有持久化的功能」+ D2 design §6 占位清单（`d2-jpa-implementation` 一期化）

## 动机

当前仍有 4 块状态纯内存，网关重启即丢：
1. **计费用量**（InMemoryBillingRepository）——UsageRecord / CostRecord 落账
2. **预算**（InMemoryBudgetRepository）——Budget CRUD + 累计用量 + alertSent
3. **配额计数器**（InMemoryQuotaRepository）——REQUEST/MODEL_TOKEN/MONEY 三维计数
4. **RBAC 角色/绑定**（infra-security InMemory 两仓储）——D1 遗留

已有持久化（不动）：Session（Redis 条件装配）、Audit/Trace/Metrics/Workflow（PG 条件装配）、ApiKey/Models（JSON 文件）。

## What

- 新增 `schema-billing-rbac.sql`（billing_records / budgets / quota_counters / rbac_roles / rbac_role_bindings，幂等 DDL）+ 独立初始化器
- 新增 5 个 PG 实现：PgBillingRepository / PgBudgetRepository / PgQuotaRepository / PgRoleRepository / PgRoleBindingRepository（JdbcTemplate 风格与 PgAuditStore 一致，无 JPA）
- 装配：复用 `observability.storage.jdbc-url` 数据源；PG bean `@ConditionalOnMissingBean` 优先，InMemory 降级保留（与 audit 同模式）
- 排序：InfraPersistenceAutoConfiguration `@AutoConfigureBefore`（InfraSecurity / BillingQuota 两配置）
- 测试：`PgBillingRbacStoresIT`（Testcontainers TimescaleDB，`-Pit` 激活；默认回归不跑）
- bootstrap `application.yml` 默认开启 jdbc-url（指向 docker-compose 的 localhost:5433）

## Non-goals

- Redis QuotaPort（gw:quota:* Lua）——维持 PG 计数器，Redis 留后续
- JPA/Flyway——继续 JdbcTemplate + ScriptUtils（与既有 observability 存储一致）
- 多实例一致性——PG 行级 UPSERT 原子性即可满足单/双实例

## 验收

- 配 PG 启动：建预算 → 落账 → **重启网关** → 预算/用量/配额/RBAC 角色全部还在
- 无 PG 启动：自动降级 InMemory，行为与现状一致
- backcompat.sh PASSED（AuthorizationServiceImplTest 零修改红线不破）

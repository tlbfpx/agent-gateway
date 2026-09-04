# Design: PG 持久化补全

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| 访问方式 | JdbcTemplate + ScriptUtils（无 JPA/Flyway） | 与既有 PgAuditStore/PgSpanStore 完全一致，零新依赖 |
| 数据源 | 复用 `observability.storage.jdbc-url` | 一套 PG 承载全部持久化；不引入第二个连接池 |
| 降级策略 | `@ConditionalOnMissingBean` + `@ConditionalOnProperty(jdbc-url)` | 无 PG 自动回 InMemory（与 audit 同模式），测试/开发零成本 |
| 装配顺序 | `@AutoConfiguration(beforeName = {BillingQuota, InfraSecurity})` | PG bean 必须先注册，否则两配置里的 InMemory 降级会抢先占位 |
| Quota 计数 | PG 行 UPSERT，period=自然日 | 跨日天然"清零"；多实例原子；重启不丢 |
| RBAC permissions | JSON 显式三型映射（agent/model/skill） | sealed Permission 不引 Jackson 多态，前后向兼容 |

## 2. 表（schema-billing-rbac.sql，全部 IF NOT EXISTS 幂等）

billing_records / budgets / quota_counters / rbac_roles / rbac_role_bindings —— DDL 见资源文件，源自 D2 design §3.2 草案。

## 3. 行为兼容

- PgQuotaRepository 限额常量 10000 + 仅累计 tokensIn，与 InMemory 完全对齐（policy 驱动差异化留二期）
- PgBudgetRepository.markAlertSent 原子 `UPDATE ... WHERE alert_sent=FALSE` 保幂等语义
- Budget 回读时冷静期已过的 suspendUntil 归 null（Domain 校验不炸）
- D1 红线零触碰：RoleRepository/RoleBindingRepository 接口签名不变，AuthorizationServiceImpl 不动

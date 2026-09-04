# Spec: PG 持久化补全（可测试条款）

#### GW-PERSIST-001 计费用量持久化
**MUST**：`observability.storage.jdbc-url` 配置时，BillingPort 由 PgBillingRepository 实现；UsageRecord（含 unit_price_in/out 单价快照）落 `billing_records`，进程重启后 queryUsage/queryCost 数据完整。
**测试**：PgBillingRbacStoresIT.billingRecord_roundtripSurvivesRestart。

#### GW-PERSIST-002 预算持久化
**MUST**：Budget CRUD 落 `budgets`（每租户一行 upsert）；accumulateUsage 为 SQL 原子自增；markAlertSent 幂等（二次 false）。
**测试**：budget_upsertAccumulateAlertSent_survivesRestart。

#### GW-PERSIST-003 配额计数器持久化
**MUST**：quota_counters 按自然日 period UPSERT 累计；重启后当日计数保留；check/reverse 语义与 InMemory 对齐（限额 10000 / 累计 tokensIn）。
**测试**：quotaCounter_consumeCheckReverse_survivesRestart。

#### GW-PERSIST-004 RBAC 持久化 + D1 零破坏
**MUST**：Role/RoleBinding 落 `rbac_roles`/`rbac_role_bindings`；sealed Permission（agent/model/skill 三型）JSON 往返无损；RoleRepository/RoleBindingRepository 接口签名不变；AuthorizationServiceImplTest 6 条零修改。
**测试**：rbacRoleAndBinding_roundtrip_survivesRestart + backcompat.sh。

#### GW-PERSIST-005 无 PG 自动降级
**MUST**：未配置 jdbc-url 时全部回退 InMemory 实现，行为与现状一致（默认回归测试即验证）。

# Tasks: 预算告警接线 + 超限降级（budget-alert-and-downgrade）

> 已全部完成（P1 · t1 · engineer-billing）。真实验证：`mvn -pl gateway-domain,gateway-application,gateway-interfaces -am test`。

- [x] **1.** `Budget` 新增 `OverLimitAction{BLOCK,DOWNGRADE}` + `fallbackModel`；null→BLOCK 规范化、DOWNGRADE 必填校验、旧 11 参构造器兼容（gateway-domain）
- [x] **2.** `BudgetGuard` 可选注入 `AlertStore`+`GatewayEvents`；80%/100% 两级 `insertFiring`（dedupKey `budget:{tenant}:{pct}` 去重）+ `budget.alert` Webhook 推送；容错不阻断
- [x] **3.** 新增 `BudgetDowngradePolicy`（application/billing）：DOWNGRADE+fallbackModel+原模型≠fallback 时返回降级目标；查询失败按 BLOCK
- [x] **4.** `ChatOrchestrator` 追加降级分支：`tryAcquireTokens` 失败 → 查策略 → 降级（过 RBAC + `budget.downgrade` 事件）或维持 429；setter 注入零构造器破坏
- [x] **5.** 装配：`BillingQuotaAutoConfiguration`（AlertStore/GatewayEvents 注入 + policy bean）、`OrchestrationConfig`（orchestrator 接 policy）
- [x] **6.** `AdminBillingController`：`BudgetRequest` 增加 `overLimitAction`/`fallbackModel`（GW-4306 校验 + 旧体兼容）
- [x] **7.** `PgBudgetRepository` 读写新列；`schema-billing-rbac.sql` 幂等加列
- [x] **8.** 测试：`BudgetTest` +3（默认 BLOCK / DOWNGRADE 缺 fallback 拒绝 / 合法）、`BudgetGuardTest` +4（两级告警 / 去重 / Webhook / 低于阈值）、`ChatOrchestratorDowngradeTest` 新增 4 例
- [x] **9.** 验证：domain 168/168、application 77/77 全绿；interfaces billing/webhook 19/19 绿

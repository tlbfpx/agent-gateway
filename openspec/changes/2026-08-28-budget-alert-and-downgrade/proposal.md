# Proposal: 预算告警接线 + 超限降级策略（budget-alert-and-downgrade）

> **状态**：✅ 已实现（P1 gateway-p1-features · t1，归档记录）
> **前置**：D2 d2-quota-and-billing（Budget/BudgetGuard/AlertStore/WebhookDispatcher 已有骨架）
> **术语锚点**：spec §21.4（预算告警）/ spec 2026-08-19 §4.3、§5.4（AlertStore dedup_key 去重）/ spec §25（Webhook 推送）

## 变更概述

本 change 把 D2 已有但**未接线**的预算告警链路打通，并补齐**超限降级**动作：

1. **BudgetGuard 两级告警接线**：`onUsageAccumulated` 在预算达到阈值（默认 80%，warning）与 100%（critical）时写入 `AlertStore`（PgAlertStore / 内存实现），并经 `GatewayEvents → WebhookEventBridge → WebhookDispatcher` 触发 Webhook 推送（`budget.alert` 事件）；两级均按 `dedupKey = budget:{tenant}:{pct}` 去重，避免每笔用量重复告警。
2. **Budget 超限动作**：新增 `overLimitAction: BLOCK（默认，现状 429）| DOWNGRADE` 与 `fallbackModel` 字段；DOWNGRADE 时 `ChatOrchestrator` 在 token 日预算超限时降级到 fallbackModel 而非直接 429（仅当原模型非 fallback 时；降级模型仍过 `checkUseModel` RBAC）。
3. **PG 持久化 + 管理 API**：`budgets` 表幂等加列（`over_limit_action`/`fallback_model`）；`POST/PUT /v1/admin/billing/budgets` 请求体支持两字段（GW-4306 校验：非法枚举 / DOWNGRADE 缺 fallbackModel）。

## 动机

1. **告警只到日志**：D2 的 BudgetGuard 仅经 `RbacChangePublisher` 借道发布一条 `BUDGET_EXCEEDED`，运营台告警流（AlertStore）与 Webhook 订阅方均感知不到预算超限。
2. **超限即 429 一刀切**：spec §21.4 对 token 超限只有「拒绝新请求」一种动作；租户宁可降级到便宜模型也不愿硬停服的场景无解。
3. **每笔用量重复告警风险**：告警必须幂等去重，否则 80% 以上区间每笔落账都会刷屏。

## What / 范围

### 做（What）

- `BudgetGuard` 可选注入 `AlertStore` + `GatewayEvents`（缺省不改变原行为）；阈值级（warning）与 100%（critical）两级 `insertFiring`，dedupKey 去重 + `budget.alert` Webhook 推送；全链路 catch+log 容错（GW-QUOTA-007）。
- `Budget` record 新增 `OverLimitAction` enum + `fallbackModel`：compact constructor 规范化 null→BLOCK；DOWNGRADE 必填 fallbackModel；保留旧 11 参构造器（存量调用点零改动）。
- 新增 `BudgetDowngradePolicy`（application/billing）：按租户 Budget 判定降级目标。
- `ChatOrchestrator` **仅追加降级分支**：`tryAcquireTokens` 失败 → 查策略 → 可降级则改用 fallbackModel（过 RBAC + 发 `budget.downgrade` 事件），否则维持原 429；setter 注入，不动既有构造器与逻辑。
- 装配：`OrchestrationConfig` 注入 policy；`BillingQuotaAutoConfiguration` 为 BudgetGuard 接 AlertStore/GatewayEvents 并注册 BudgetDowngradePolicy bean。
- `PgBudgetRepository` 读写新列 + `schema-billing-rbac.sql` 幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。
- `AdminBillingController.BudgetRequest` 增加 `overLimitAction`/`fallbackModel`（旧请求体兼容）。

### 不做（Non-goals）

- 告警恢复（firing→resolved）自动流转：预算为单调累加语义，日清零后新周期 dedupKey 沿用即静默，恢复流转二期与 AlertEngine 统一处理。
- 用户级（UserId）预算降级：一期租户级。
- 前端 UI 表单字段：运营台页面另行跟进。
- 按模型细粒度 fallback 链：单一 fallbackModel。

## 验收（已达成）

- `mvn -pl gateway-domain,gateway-application,gateway-interfaces -am test`：domain 168/168、application 77/77 全绿；interfaces 中 billing/webhook 相关 19/19 绿。
- 新增测试：`ChatOrchestratorDowngradeTest`（4 例：BLOCK 429 / DOWNGRADE 降级 / 原模型即 fallback 仍 429 / 无预算维持现状）、`BudgetGuardTest` +4（80% warning + Webhook / 100% 两级 / 重复去重 / 低于阈值不写）、`BudgetTest` +3（null→BLOCK / DOWNGRADE 缺 fallbackModel 拒绝 / 合法构造）。

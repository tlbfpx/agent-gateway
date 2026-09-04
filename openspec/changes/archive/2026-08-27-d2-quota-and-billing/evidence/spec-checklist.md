# D2 spec-checklist — 10 条 SHALL 逐条核验（归档闸门 ⑫）

> 核验日期：2026-08-27 · worktree `feature-d2-quota` · 对照 `openspec/changes/d2-quota-and-billing/spec.md`

## 第 1 组：record 类型化与 Port 契约

### ✅ GW-QUOTA-001 UsageRecord / CostRecord / Budget 类型化
- `gateway-domain/billing/UsageRecord.java`：recordId/TenantId/UserId/ModelId/agentName/timestamp/tokensIn/tokensOut/cost + **unitPriceIn/unitPriceOut 单价快照**（canonical constructor 校验非负）
- `CostRecord.java`：tenant × user × model × agent × date 五元组按日聚合 + currency 预留
- `Budget.java`：13 字段含 AlertThreshold + suspendAction + suspendUntil（SUSPEND 必须 future 校验）
- 测试：`UsageRecordTest`(6) / `CostRecordTest` / `BudgetTest` — 边界值 + 单价快照断言

### ✅ GW-QUOTA-002 QuotaPort + BillingPort 契约 + 租户隔离
- `BillingPort`：recordUsage / queryUsage / queryCost / exportUsage（UsageQuery 必含 TenantId）
- `QuotaPort`：check / consume / reverse / snapshot
- `InMemoryBillingRepository`（ConcurrentHashMap 租户隔离）/ `InMemoryQuotaRepository`
- 测试：`BillingPortContractTest` / `QuotaPortContractTest`（10 用例，跨租户隔离）

### ✅ GW-QUOTA-003 QuotaDecision sealed
- sealed interface permits Allowed / Throttled / Suspended / Rejected；Java 21 编译期 exhaustiveness
- QuotaGate.enforce switch 四分支全映射：Rejected→GW-4304/429、Suspended→GW-4305/403、Throttled 放行降速
- 测试：`QuotaDecisionTest` + `QuotaGateTest`（4 decision 映射）

### ✅ GW-QUOTA-004 QuotaPolicy 三档可配
- `QuotaPolicy(TenantId, ModelId, QuotaDimension, QuotaAction, thresholdPct, limitValue)`；QuotaAction {ALERT,THROTTLE,SUSPEND}；阈值∈[1,100]
- SUSPEND 显式管理员 + 5 分钟冷静期（Budget.suspendUntil 校验）；**REST 自动配置拒绝 SUSPEND（AdminBillingControllerTest.createBudget_autoSuspendRejected_GW4306）**
- 测试：QuotaPolicy 校验 + 冷静期约束

## 第 2 组：计费数据流与策略动作

### ✅ GW-QUOTA-005 单一数据源 ObservabilityHooks → BillingPort
- `MicrometerObservabilityHooks.onTokens` 可选注入 BillingPort，异步 recordUsage；失败 catch + log warn
- `BillingEngine`：单价快照（ModelPriceRegistry 函数式注入，未知模型回退零价）
- 测试：`MicrometerObservabilityHooksTest` + `BillingEngineTest`(5)

### ✅ GW-QUOTA-006 QuotaGate 前置拦截
- `QuotaGate.check(tenant, model, predicted)` 三维独立判定（REQUEST/MODEL_TOKEN/MONEY）短路
- `QuotedOrchestrator` 装饰器包装 ChatOrchestrator——**既有方法零修改**（红线保持）
- 测试：`QuotaGateTest` + `QuotedOrchestratorTest`(6)

### ✅ GW-QUOTA-007 BudgetGuard 异步预算校验 + 告警
- 落账后 BillingEngine → BudgetGuard.onUsageAccumulated（本 Chunk 补上接线）
- 超阈值 && !alertSent → RbacChangePublisher.publish（BUDGET_EXCEEDED payload in actor）
- 幂等（markAlertSent 二次 false）+ 失败容错 catch + log warn
- 测试：`BudgetGuardTest`（80% 触发 / 二次不重发 / 失败不阻断）+ `BillingEndToEndTest.fullLifecycle`

## 第 3 组：REST 契约 + 兼容性

### ✅ GW-QUOTA-008 AdminBillingController REST
- GET /costs（GW-4301）、GET /costs/total、GET /usage/export?format=CSV（GW-4303）、GET/POST/PUT/DELETE /budgets（GW-4302/GW-4306）
- X-API-Key + X-Tenant-Id 鉴权（与 D1 同款）
- 测试：`AdminBillingControllerTest`(14) — happy + 错误码 + 租户隔离

### ✅ GW-QUOTA-009 AdminMetricsController 1500 硬编码替换
- cost() 优先 BillingPort.queryUsage 真实记账（按模型均摊），无数据/故障降级估算 1500；接口契约零变化
- 测试：`AdminMetricsControllerMetricsTest`(3) — 真实 168≠1500 / 降级 1500 / 故障不抛；既有 `AdminMetricsControllerTest`(10) 零修改全绿

### ✅ GW-QUOTA-010 与 D1 RBAC 零破坏
- `scripts/check-rbac-backcompat.sh master` 三项 PASSED（方法存在 + 0 删除 + 6 tests 绿）— B 阶段首尾双校验
- AuthorizationService / AdminPolicyController / RbacFilter 零改动（git diff master 证实）

## 错误码段自检
- D2 使用 GW-43xx（4301~4306），与 D1 GW-1xxx/42xx、D3 GW-5xxx、D4 GW-45xx/6xxx/7xxx 零冲突 ✅

## 二期占位（design §6）
- SUSPEND 接入 AuthFilter → 二期（本期仅 Budget.suspendAction/suspendUntil 标志 + REST 拒绝自动 SUSPEND）
- Redis QuotaPort（gw:quota:* 前缀）→ 二期（本期 InMemory）
- MQ 真实分发 → 二期（UsageWriter ArrayBlockingQueue drainer）

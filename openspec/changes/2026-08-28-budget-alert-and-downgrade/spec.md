# Spec: 预算告警接线 + 超限降级（可测试条款）

#### GW-BUDGET-001 阈值级告警（warning）
**MUST**：BudgetGuard.onUsageAccumulated 累加用量后，日用量占比 ≥ alertThreshold（默认 80%）时，向 AlertStore 写入一条 severity=warning、state=firing 的告警记录（dedupKey=budget:{tenant}:{thresholdPct}），并经 GatewayEvents 推送 budget.alert 事件；占比低于阈值不写任何记录。
**测试**：BudgetGuardTest.p1_alertStore_达80阈值写warning告警并推Webhook / p1_alertStore_低于阈值不写告警。

#### GW-BUDGET-002 100% 严重告警（critical）
**MUST**：日用量占比 ≥ 100% 时，除 warning 外额外写入一条 severity=critical 的 firing 告警（dedupKey=budget:{tenant}:100）。
**测试**：BudgetGuardTest.p1_alertStore_达100写critical_两级各一条。

#### GW-BUDGET-003 dedupKey 去重
**MUST**：同租户同级别已存在 state=firing 的告警记录时，后续用量累加不得重复插入该级告警（两级各自独立去重）；告警链路任何异常均被吞掉（catch+log），不阻断计费落账。
**测试**：BudgetGuardTest.p1_alertStore_重复用量不重复告警_去重 / onUsageAccumulated_publisherFailure_doesNotPropagate。

#### GW-BUDGET-004 DOWNGRADE 降级且过 RBAC
**MUST**：Budget.overLimitAction=DOWNGRADE 且 fallbackModel 非空、原请求模型 ≠ fallbackModel 时，token 日预算超限不得返回 429，而是改用 fallbackModel 完成编排（仍执行 checkUseModel RBAC 校验，并发布 budget.downgrade 事件）；overLimitAction 缺省或 BLOCK 时维持 429 现状；原模型即 fallbackModel 时仍 429。
**测试**：ChatOrchestratorDowngradeTest.DOWNGRADE_配额超限降级到fallback模型 / 默认BLOCK_配额超限仍429 / DOWNGRADE_原模型即fallback_仍429 / 无预算_维持429现状。

#### GW-BUDGET-005 DOWNGRADE 校验拒绝
**MUST**：DOWNGRADE 缺 fallbackModel（null 或空白）时 Budget 构造抛 IllegalArgumentException；overLimitAction 为 null 时规范化为 BLOCK。管理 API POST/PUT /v1/admin/billing/budgets 携带非法 overLimitAction 枚举或 DOWNGRADE 缺 fallbackModel 时返回 400（GW-4306）。
**测试**：BudgetTest.downgrade_缺fallbackModel_拒绝 / overLimitAction_null默认BLOCK；AdminBillingControllerTest（既有 GW-4306 校验路径）。

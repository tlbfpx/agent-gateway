# D2 Chunk 3 + 4 验收（编排装饰器 + REST + UI + E2E）

## Chunk 3 增量
- `QuotedOrchestratorTest`：6 用例（透传 / 租户解析 / GW-4304 短路 / GW-4305 短路 / 保守预测用量 / Flux 契约）
- `AdminMetricsControllerMetricsTest`：3 用例（真实 168≠1500 / 无 BillingPort 降级 1500 / 计费故障容错降级）
- `AdminBillingControllerTest`：14 用例（costs / total / export / budget CRUD / GW-4301/4302/4303/4306 / SUSPEND 拒绝自动配置 / 租户隔离）

## Chunk 4 增量
- UI：`lib/api/billing.ts` + `pages/Budgets.tsx`（预算 CRUD + 进度 + 最近记账表）+ 路由 `/budgets` + 侧栏「预算管理」
  - CostCenter 沿用既有 `/cost` 页（其数据源 metrics/cost 已被 GW-QUOTA-009 换成真实记账）
  - `npm run build` ✅；vitest 30 files / 203 tests 全绿 ✅
- `BillingEndToEndTest`：3 用例（完整生命周期：建预算→落账→告警→幂等→删恢复 / Chargeback 导出 / 租户隔离）
- 接线补强：BillingEngine 落账后触发 BudgetGuard（design §4.1，GW-QUOTA-007 闭环）

## spec SHALL 进展（累计 10/10）
- ✅ GW-QUOTA-001~004（Chunk 1）
- ✅ GW-QUOTA-005~007（Chunk 2 + 本 Chunk 接线闭环）
- ✅ GW-QUOTA-008 REST 契约 + 错误码段零冲突
- ✅ GW-QUOTA-009 1500 硬编码替换（真实数据优先 + 降级容错）
- ✅ GW-QUOTA-010 backcompat.sh PASSED（B 阶段首尾双校验）

## 关键实现决策（plan 偏差记录）
1. QuotedOrchestrator 以 `orchestrate(ChatRequest, apiKey, tenantId)` 透传（plan 草稿的 4 参 chat 方法不存在，按真实签名适配）
2. AdminMetricsController cost() 采用「真实记账按模型均摊 + 估算降级」双口径（保证既有 10 条测试零修改 + 契约不变）
3. Budgets 页含「超额动作」下拉（ALERT/THROTTLE）——SUSPEND 不在 REST 可选项（GW-4306 拒绝自动配置）

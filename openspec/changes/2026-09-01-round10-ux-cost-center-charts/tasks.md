# Tasks: 成本中心图表化

## T1. OpenSpec 4 件套
- [x] proposal.md / design.md / spec.md / tasks.md

## T2. 新建 chart 组件（零依赖 SVG）
- [x] `src/components/charts/TimeseriesChart.tsx` — 折线图（复用 AreaBarChart 数据契约，简化版）
- [x] `src/components/charts/ModelSharePie.tsx` — 模型占比饼图
- [x] `src/components/charts/PeriodCompareBar.tsx` — 同期对比柱图

## T3. lib/api/cost.ts
- [x] `deriveTimeseries(report)` — from CostReport.byDay（按 range 自动补 0）
- [x] `deriveBreakdown(report, dim)` — from byModel/byTenant/byKey
- [x] `deriveCompare(report)` — 本期 vs 上期（按 range 一分为二）
- [x] `getBillingTimeseries / getBillingBreakdown / getBillingCompare` 后端占位（待 Round 11）

## T4. 改造 CostCenter.tsx
- [x] range 选项扩为 24h / 7d / 30d / **90d**
- [x] StatCard 下插入 Row1: TimeseriesChart(span=16) + ModelSharePie(span=8)
- [x] 插入 Row2: PeriodCompareBar(span=24)
- [x] URL 参数 `?range=` 兼容 90d
- [x] ReportRange 类型扩为含 90d（usage.ts + loadCostReport + aggregateCostFromAudit）

## T5. 测试
- [x] `tests/CostCenter.test.tsx` — 12 个用例覆盖 7 项 spec
- [x] `npx vitest run tests/CostCenter.test.tsx` 12/12 绿
- [x] `npx vitest run tests/CostCenter.test.tsx tests/cost.test.tsx tests/cost-live-contract.test.ts tests/cost-scheduled-report.test.tsx` 26/26 绿
- [x] `npx tsc --noEmit -p .` 通过（无 CostCenter/cost/Timeseries/ModelShare/PeriodCompare 错误）

## T6. 验收
- [x] 3 图表布局描述（见摘要）
- [x] 测试输出 → 主摘要
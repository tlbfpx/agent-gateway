# Proposal: 成本中心图表化（round10-ux-cost-center-charts）

> **状态**：Round 10 UX 增量 — 修复附录 B-10 单期表问题
> **来源**：UI 评审 Cost Center 单期表无图表(4 张 StatCard + 1 个 Tabs + 1 张表) → 加 3 类图

## 动机

Cost Center 当前只能看「这期花了多少 / 谁花最多」，缺少**趋势 / 占比 / 同期对比**三类认知维度：

1. **趋势**：只看本期数字，无法判断成本是涨是跌；运营需要「近 7/30 天每日成本曲线」
2. **占比**：明细表里已有百分比，但需要一张图直观看到「GPT-4o 占 60%」
3. **同期对比**：本周 vs 上周 / 本月 vs 上月 — 决策的关键问题「这个月是不是超预算了」

## What

### 前端

- `src/components/charts/TrendPanel.tsx` — 复用 Dashboard 既有 4 联图组件;Cost Center 复用其中"成本曲线"单图(改为可配置 series)
- 新建 `src/components/charts/ModelSharePie.tsx` — 模型占比饼图(SVG,零依赖,同 TrendPanel 风格)
- 新建 `src/components/charts/PeriodCompareBar.tsx` — 同期对比柱状图(本期 vs 上期)
- `src/pages/CostCenter.tsx` — 在 4 张 StatCard 下、 Tabs 上插入 2 行图表:
  - Row1: TrendPanel(左 16) + ModelSharePie(右 8)
  - Row2: PeriodCompareBar(全宽 24)
- 时间范围切换 7d / 30d / 90d 影响 3 个图表

### 后端

- 暂不实装;Round 10 仅前端 + mock
- 计划后端:`GET /v1/admin/billing/timeseries` + `/breakdown` + `/compare`(留 Round 11)

### lib API

- 新建 `src/lib/api/cost.ts` — `getBillingTimeseries / getBillingBreakdown / getBillingCompare`,带 mock fallback(从 `loadCostReport().byDay` 派生)

## Non-goals

- 不实装后端 API(留 Round 11 与 backend-developer 协调)
- 不引入新依赖(保持零依赖 SVG,与 TrendPanel/AreaBarChart 一致)
- 不改 Dashboard / 其他页面

## 验收

- CostCenter 页面新增 3 个图表区块,渲染稳定
- 时间范围切换 7d / 30d / 90d → 3 图同步刷新
- 同期对比:本周 vs 上周;本月 vs 上月
- 测试:`CostCenter.test.tsx` 覆盖图表渲染 + Tabs/Range 联动 + 空态降级
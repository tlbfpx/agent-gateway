# Design: 成本中心图表化

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| 图表库 | 零依赖 SVG(自写) | 与 TrendPanel / AreaBarChart 一致;不引入 recharts/echarts(未安装);体积 0 |
| 折线图 | 复用 AreaBarChart + 改造为接受 metric='cost' | 已有曲线渲染逻辑;通过 prop 切换 n/cost |
| 饼图 | 新建 ModelSharePie.tsx | TrendPanel 无饼图能力;饼图零依赖 30 行 SVG 足够 |
| 同期对比柱图 | 新建 PeriodCompareBar.tsx | 业务专用,本期/上期并列柱 + 差值标注 |
| 时间范围 | 7d / 30d / 90d (CostCenter 已有 24h,扩为 4 档) | 运营反馈:24h 太短,30d/90d 看趋势 |
| 数据源 | mock(从 byDay 派生) | 后端 API 留 Round 11;前端不阻塞 |
| URL 同步 | `?range=7d&dim=tenant` 已有;新增 `?chart=` 控制图表维度 | 与 Round 4 URL 同步一致 |

## 2. 数据流

```
CostCenter 挂载 / range 变化
  ↓
loadCostReport(range)  (已有)
  ├─ live: GET /admin/metrics/cost
  └─ 派生:audit 聚合 + PRICE_TABLE 估算
  ↓
CostReport.byDay / byModel / byTenant
  ↓
派生三个图表数据:
  ├─ trend: byDay → [{ t:'09-01', n:costCny }]
  ├─ pie: byModel → [{ label, value }]
  └─ compare: byDay → { current:sum, previous:sum, delta }
  ↓
<TimeseriesChart> + <ModelSharePie> + <PeriodCompareBar>
```

## 3. 新组件 props

```ts
// 折线图（从 AreaBarChart 抽 prop）
<TimeseriesChart points={[{t,n}]} title="成本曲线 · 7d" unit="元/天" />

// 饼图
<ModelSharePie slices={[{label, value, color}]} total={1234.56} />

// 柱图
<PeriodCompareBar
  currentLabel="本周" currentValue={123}
  previousLabel="上周" previousValue={98}
  deltaPct={25.5} trend="up"
/>
```

## 4. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 后端 API 还未实装,纯 mock | mock 函数明确返回 from-report 结构,后端 ready 后只换实现 |
| 90d 数据点过多(90 个) AreaBarChart 渲染慢 | 90d 模式渲染时 labels i%10===0,与现有相同 |
| 饼图色板与品牌色冲突 | 复用 StatCard accent 色板(amber/blue/green/red/purple) |
| URL 加 ?chart= 破坏既有解析 | 解析容错:非法值忽略 |

## 5. 涉及文件

| 文件 | 改动 |
|---|---|
| `agent-gateway-ui/src/pages/CostCenter.tsx` | 加 3 图表 + range 4 档 |
| `agent-gateway-ui/src/components/charts/ModelSharePie.tsx` | 新建 |
| `agent-gateway-ui/src/components/charts/PeriodCompareBar.tsx` | 新建 |
| `agent-gateway-ui/src/components/charts/TimeseriesChart.tsx` | 新建(复用 AreaBarChart 数据契约) |
| `agent-gateway-ui/src/lib/api/cost.ts` | 新建(getBillingTimeseries + mock) |
| `agent-gateway-ui/tests/CostCenter.test.tsx` | 新建 |
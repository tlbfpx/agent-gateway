# Spec: 成本中心图表化（可测试条款）

#### GW-UX-CEST-001 折线图渲染
**MUST**：`CostCenter` 在 4 张 StatCard 下渲染 1 张「成本曲线」图，含日期/成本点。
**测试**：CostCenter.test.tsx rendersTrendChart。

#### GW-UX-CEST-002 模型占比饼图
**MUST**：饼图渲染来自 `report.byModel` 的占比;hover 显示百分比。
**测试**：rendersModelPie / pieSegmentsMatchByModel。

#### GW-UX-CEST-003 同期对比柱图
**MUST**：本周 vs 上周 / 本月 vs 上月 显示并列柱 + 差值%。
**测试**：rendersPeriodCompare / deltaPctCorrect。

#### GW-UX-CEST-004 时间范围切换
**MUST**：7d / 30d / 90d 切换时 3 个图表同步刷新数据。
**测试**：rangeChangeReloadsCharts。

#### GW-UX-CEST-005 空态降级
**MUST**：报告为空时图表区显示 EmptyState 占位,不渲染坐标轴。
**测试**：emptyReportShowsEmptyState。

#### GW-UX-CEST-006 URL 同步
**MUST**：`?range=30d` 进入页面后图表使用 30d 数据;`?chart=pie` 默认显示饼图区块。
**测试**：urlRangeInitializesCharts。

#### GW-UX-CEST-007 零依赖 SVG
**MUST**：3 个图表均用 `<svg>` + antd Tooltip 实现,不引入 recharts/echarts。
**测试**：packagesJsonNoChartDeps / sourceImportsOnlyReactAntd。
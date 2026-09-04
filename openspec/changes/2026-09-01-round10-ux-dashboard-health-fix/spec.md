# Spec: Dashboard 健康判定修复

#### GW-UX-101 warning 分支识别
**MUST**：`Dashboard.tsx` 健康汇总循环对 `'WARN' / 'WARNING' / 'DEGRADED'` 状态映射为 `'warning'` 并设置 `hasWarn = true`。
**测试**：Dashboard.test.tsx warningBranchMapsAndSetsHasWarn。

#### GW-UX-102 slow 状态可达
**MUST**：当至少一个组件为 warning 且无 error 时，`healthStatus` 被设为 `'slow'`，UI 渲染 `HEALTH_LABEL['slow']` = "部分降级"。
**测试**：Dashboard.test.tsx slowStatusReachableAndRenders / downOverridesSlow。

#### GW-UX-103 派生 warning 数据源
**MUST**：对 readiness `details.latencyMs`（DB / Provider p99 等）做阈值分段 — `100~500ms` 视为 warning；超过 500ms 视为 error；缺失时保持 success（不误报）。
**测试**：Dashboard.test.tsx latencyBasedWarning。

#### GW-UX-104 cache 命中率派生
**MUST**：cache 命中率 `0.3~0.6` 视为 warning；`< 30%` 视为 error；`≥ 60%` 或未拉取视为 success。
**测试**：Dashboard.test.tsx cacheRateBasedWarning。

#### GW-UX-105 down 优先级最高
**MUST**：混合 warning + down 时 `healthStatus` 仍为 `'down'`（避免 UI 误导）。
**测试**：Dashboard.test.tsx downOverridesWarning。
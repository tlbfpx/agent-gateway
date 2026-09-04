# Proposal: Dashboard 健康判定修复（round10-ux-dashboard-health-fix）

> **状态**：Round 10 UX 修订 — 单文件 fix
> **来源**：OpenSpec 报告附录 B-7（Dashboard 健康判定死代码）
> **目标**：Dashboard.tsx:104-117 `hasWarn` 永 false；解锁 `'slow'` 状态显示路径

## 动机

当前 Dashboard 的健康判定逻辑有两处缺陷：

1. **`hasWarn` 永为 false**：循环里只检查 `'error'`，从未对 `s === 'warning'` 赋值；
   `hasWarn && !allUp` 的 `slow` 分支永远是死代码，`HEALTH_LABEL['slow']` 永远不显示。
2. **健康判定只看 readiness 上/下**：后端 `HealthController#ready` 只返回 `UP`/`EMPTY`，
   没有任何 `'warning'` 数据源 → 即使前端逻辑正确也永远拿不到 warning。

结果是 UI 上的"部分降级"状态从未出现过；用户看到的要不是"全部正常"，要不是"存在故障"，
无法区分"有一个非关键组件轻微劣化但核心仍可用"的中间态。

## What

### 前端
- `agent-gateway-ui/src/pages/Dashboard.tsx`：
  1. 在 health 汇总循环中识别 `'warning'` 分支 → 写 `hasWarn = true`；
  2. 新增"健康数据源"聚合：基于后端 metrics（`/actuator/metrics/...`）和 readiness 报告，
     对 cache 命中率 / DB 延迟 / Provider 延迟 / Agent 健康度等派生指标判定 warning；
  3. 修复后逻辑：`allUp → 'up'` / `hasWarn && !hasError → 'slow'` / `else → 'down'` / 空 → `'unknown'`。

### 测试
- `agent-gateway-ui/tests/Dashboard.test.tsx`：覆盖 4 种组合
  （全 up / 全 down / 部分 warning / 混合 warning+down）。

## Non-goals

- 不改后端 HealthController（warning 派生在前端做，符合"轻后端 + 富前端"架构）；
- 不动其他页面、其他 hook；
- 不做实时刷新（仍走 30s 定时）。

## 验收

- `setHealthStatus('slow')` 在部分组件 warning 时被调用；
- `HEALTH_LABEL['slow']` = "部分降级" 在 UI 实际出现；
- vitest 全绿；新增 Dashboard 测试覆盖 4 种状态组合。
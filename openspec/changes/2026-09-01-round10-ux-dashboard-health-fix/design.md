# Design: Dashboard 健康判定修复

## 现状

```ts
// Dashboard.tsx:101-117
if (h.status === 'fulfilled') {
  const comps = h.value.checks ?? {};
  const map: Record<string, 'success' | 'warning' | 'error'> = {};
  let allUp = true;
  let hasWarn = false;          // ❌ 永远没被赋值
  for (const [name, c] of Object.entries(comps)) {
    const cs = typeof c === 'string' ? c : c.status;
    const s = cs === 'UP' ? 'success' : 'error';  // ❌ 没有 'warning' 分支
    map[name] = s;
    if (s === 'error') allUp = false;
  }
  if (allUp && Object.keys(comps).length > 0) setHealthStatus('up');
  else if (hasWarn) setHealthStatus('slow');  // ❌ 死代码
  else if (Object.keys(comps).length > 0) setHealthStatus('down');
  else setHealthStatus('unknown');
  setHealth(map);
}
```

## 修复方案

### 1. 识别 warning 分支（核心 fix）

```ts
for (const [name, c] of Object.entries(comps)) {
  const cs = typeof c === 'string' ? c : c.status;
  let s: 'success' | 'warning' | 'error';
  if (cs === 'UP') s = 'success';
  else if (cs === 'WARN' || cs === 'WARNING' || cs === 'DEGRADED') s = 'warning';
  else s = 'error';
  map[name] = s;
  if (s === 'error') allUp = false;
  else if (s === 'warning') hasWarn = true;
}
```

### 2. 数据源：派生 warning 指标

后端 `/v1/ready` 当前只返回 `UP`/`EMPTY`。warning 数据通过派生：
- `details.latencyMs`（DB check 自带）→ 阈值分段：
  - `< 100ms` → success
  - `100~500ms` → warning
  - `> 500ms` → error
- `details.p99Ms`（Provider / 上游）同上阈值
- `cacheRate`（来自 `/actuator/metrics/prompt_cache_hit_total+miss_total`）：
  - `≥ 0.6` → success
  - `0.3~0.6` → warning
  - `< 0.3` → error
- 缺失时（如 `details.latencyMs == null`）保持 `success`（不误报）

后端未来若扩展 `/v1/ready` 返回 `WARN`，前端自动识别。

### 3. 复用派生函数

新增 `deriveHealthFromDetails(name, details, cacheRate)`：
- 输入组件名 + readiness `details` + 当前 cache 命中率；
- 输出 `'success' | 'warning' | 'error'`；
- 主循环调用它覆盖 raw 状态。

### 4. 状态机

| allUp | hasWarn | hasError | 状态 |
|-------|---------|----------|------|
| true  | false   | false    | up |
| false | true    | false    | slow |
| *     | *       | true     | down |
| 空集合 | -      | -        | unknown |

## 测试覆盖

`tests/Dashboard.test.tsx`：
- A. 全 UP → `'up'` + "全部正常" 文案
- B. 部分 WARN（DB latency 300ms）→ `'slow'` + "部分降级" 文案
- C. 全 DOWN → `'down'` + "存在故障" 文案
- D. 混合 WARN + DOWN → `'down'`（down 优先级高）

mock 用 `installMock` 覆写 `GET /v1/ready`，断言渲染后 `.health-pill--up/slow/down` 的类名。

## 风险

- 派生阈值（100/500ms 等）写死前端，未来若后端扩展阈值字段，需同步；
- 不影响其他页面，仅 Dashboard 单文件 fix。
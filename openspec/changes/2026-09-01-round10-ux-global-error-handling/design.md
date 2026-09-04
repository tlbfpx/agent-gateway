# 设计 · Round 10 B-2 全局错误处理

## 1. 两层错误边界的职责切分

```
AppShell
└── ErrorBoundary scope="app"        ← 保留：最外层白屏兜底（Sidebar/Header 崩了）
    └── Content
        └── QueryErrorBoundary        ← 新增：替换原 ErrorBoundary scope="route"
            └── <Outlet/>             ← 路由页面
```

按「具体错误类型」分发：

| 层 | 组件 | 捕获 | 降级 UI |
|----|------|------|---------|
| app | `ErrorBoundary` | 壳层崩溃 | 全屏卡片 + 错误堆栈 + 返回首页 |
| route | `QueryErrorBoundary` | 页面渲染/数据 throw | 整页 `ErrorState` + 原地重试 |

`QueryErrorBoundary` 的 fallback 用 `ErrorState`（已有组件，红框 + 重试按钮），
视觉上与列表页内联错误一致 —— 用户看到的「加载失败」长得一样，无论错误从
渲染期还是异步来。

## 2. QueryErrorBoundary 契约

```ts
interface Props {
  children: ReactNode;
  fallback?: ReactNode;              // 覆盖默认 ErrorState
  onError?: (e: Error, i: ErrorInfo) => void;
  resetKeys?: unknown[];             // 值变化时自动 reset（如路由 pathname）
}
```

- `getDerivedStateFromError` 置 `hasError`（渲染期同步）。
- `componentDidCatch` 里 `notifyError(error, 'route')` —— 错误同时进通知中心。
- `reset()` 清空 state 重挂子树；**不做「重试过就跳首页」限制**
  （`ErrorBoundary` 的 `retried` 逻辑对路由级过于激进，用户改了筛选条件
  再重试是合理路径）。
- `componentDidUpdate` 比较 `resetKeys` 浅相等，变化则自动 reset。

## 3. notifyError 去重策略

放在 `lib/request.ts`（与 `ApiError` 同文件，调用方 import 一处即可）。

```ts
export function notifyError(err: unknown, context?: string): void
```

- **去重 key** = `${context}::${status}::${message}`，5 分钟 TTL。
  命中则**完全静默**（不 toast 不通知）——避免 30s 轮询的 Traces 页
  在后端挂掉时每半分钟弹一条，10 分钟刷屏 20 条。
- TTL 表用模块级 `Map<string, number>`，写入时顺带清理过期项（无需定时器）。
- **等级映射**：`status >= 500 || status === 0` → `critical`；其余 → `warning`。
- **双通道**：
  1. `addNotification({ level, title, description, source: 'system', dedupKey })`
     → 常驻通知中心（localStorage 持久化，用户主动清才消失）。
  2. `message.error(...)` → 即时 toast，保留「立刻看见」的反馈。
- **循环依赖**：`request.ts` → `hooks/useNotifications.ts` 是单向的
  （useNotifications 不 import request），安全静态 import。
  antd `message` 静态 import 在 jsdom 下可用，无需动态加载。
- `AbortError` / `timeout` 主动取消场景直接返回，不打扰用户。

## 4. 三个列表页迁移

Round 10 B-1（`useResourceList` hook）**尚未落地**，故本 change 保留各页
自身的 `load()`，仅在 catch 分支追加 `notifyError(e, '<页面名>')`。
B-1 落地后把这行收进 hook 即可，页面侧无需再改。

| 页面 | 改动 |
|------|------|
| `Models/List.tsx:92` | catch 加 `notifyError(e, '模型列表')`；渲染已是 XOR，不动 |
| `ApiKeys/List.tsx:57` | catch 加 `notifyError(e, 'API Key 列表')`；**删掉 :393 独立 ErrorState，改为 :527 Table 处 XOR** |
| `Traces.tsx:82` | catch 加 `notifyError(e, '调用链列表')`；503 未配置存储走既有引导页分支，**不进通知**（预期状态非故障） |

## 5. 测试策略

| 文件 | 覆盖 |
|------|------|
| `tests/query-error-boundary.test.tsx` | throw 子组件 → 渲染 ErrorState；点重试 → 恢复正常子树；resetKeys 变化自动恢复；fallback 覆盖 |
| `tests/notify-error.test.ts` | 通知中心收到条目；5 分钟内同错误去重；不同 context 不去重；TTL 过期后放行；AbortError 静默；5xx→critical / 4xx→warning |
| `tests/apikeys-error-xor.test.tsx` | 加载失败时 ErrorState 出现且 Table 不出现（回归 B-2 双渲染 bug） |

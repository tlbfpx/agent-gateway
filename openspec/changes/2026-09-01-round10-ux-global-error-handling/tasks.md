# 任务 · Round 10 B-2 全局错误处理

## 1. 错误边界

- [ ] 新建 `src/components/framework/QueryErrorBoundary.tsx`
  - [ ] class 组件 + `getDerivedStateFromError` + `componentDidCatch`
  - [ ] 默认 fallback 渲染整页 `ErrorState`（`EmptyState.tsx` 导出）+ 重试
  - [ ] `fallback` / `onError` / `resetKeys` props
  - [ ] `componentDidUpdate` 浅比较 `resetKeys` 自动 reset
  - [ ] `componentDidCatch` 内调 `notifyError(error, 'route')`
- [ ] 改 `src/layouts/AppShell.tsx`：内层 `ErrorBoundary scope="route"` → `QueryErrorBoundary`
  - [ ] 外层 `ErrorBoundary scope="app"` 保持不变
  - [ ] `resetKeys={[pathname]}`：路由切换自动清错误

## 2. 错误通知

- [ ] 改 `src/lib/request.ts`
  - [ ] `import { addNotification } from '../hooks/useNotifications'`
  - [ ] `import { message } from 'antd'`
  - [ ] 模块级 `Map<string, number>` 去重表 + 5 分钟 TTL + 写入时清理过期
  - [ ] `export function notifyError(err: unknown, context?: string): void`
  - [ ] AbortError / timeout 静默短路
  - [ ] 5xx / 网络错误 → critical；4xx → warning
  - [ ] `export function __resetErrorDedup()`（仅测试用，重置去重表）

## 3. 列表页迁移

- [ ] `src/pages/Models/List.tsx` — catch 加 `notifyError(e, '模型列表')`
- [ ] `src/pages/ApiKeys/List.tsx`
  - [ ] catch 加 `notifyError(e, 'API Key 列表')`
  - [ ] 删除 `:393` 处独立 `{error && <ErrorState/>}`
  - [ ] `:527` Table 改为 `error ? <ErrorState onRetry={load}/> : <Table/>` XOR
- [ ] `src/pages/Traces.tsx` — catch 加 `notifyError(e, '调用链列表')`，503 分支跳过

## 4. 测试

- [ ] `tests/query-error-boundary.test.tsx`
- [ ] `tests/notify-error.test.ts`
- [ ] `tests/apikeys-error-xor.test.tsx`
- [ ] `npx vitest run` 全绿
- [ ] `npm run typecheck` 无新增错误

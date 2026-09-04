# 规格 · Round 10 B-2 全局错误处理

## FE-ERR-001 路由级错误边界

- **必须** 在 `AppShell` 的 `<Content>` 内、`<Outlet/>` 外包裹 `QueryErrorBoundary`。
- 子树渲染期抛错时 **必须** 渲染整页 `ErrorState`（含错误消息与「重试」按钮），
  **不得** 只留空白区域。
- 点击「重试」**必须** 清空边界内部错误状态并重新挂载子树；重试成功后
  **必须** 显示正常内容。
- `resetKeys` 中任一元素浅比较发生变化时 **必须** 自动重置错误状态。
- 捕获到的错误 **必须** 同时写入通知中心（`context = 'route'`）。
- 外层 `ErrorBoundary scope="app"` **必须** 保留，用于壳层（Sidebar/Header）崩溃兜底。

## FE-ERR-002 错误通知中心

- `notifyError(err, context?)` **必须** 将错误写入常驻通知中心
  （`addNotification`，localStorage 持久化），**不得** 仅依赖 3 秒自动消失的 toast。
- **必须** 同时调用 `message.error` 提供即时反馈。
- 去重 key **必须** 为 `${context}::${status}::${message}`，TTL **5 分钟**；
  TTL 内重复错误 **必须** 完全静默（既不 toast 也不写通知中心）。
- TTL 过期后同一错误 **必须** 重新通知。
- `status >= 500` 或非 `ApiError`（网络/未知）**必须** 记为 `critical`；
  其余 **必须** 记为 `warning`。
- `AbortError`、超时取消 **必须** 静默返回（用户主动取消不是故障）。
- `notifyError` 在无 DOM 环境下 **不得** 抛出未捕获异常。

## FE-ERR-003 列表页错误呈现口径

- `Models/List.tsx`、`ApiKeys/List.tsx`、`Traces.tsx` 的加载失败分支
  **必须** 调用 `notifyError` 并附带页面级 context。
- 错误态与数据表格 **必须** 互斥（XOR）：`error` 非空时渲染 `ErrorState`，
  **不得** 同时渲染空 `<Table>`。
- `ErrorState` **必须** 提供 `onRetry` 重新触发该页 `load()`。
- Traces 的 503「未配置持久化存储」**不得** 计入错误通知
  （属预期未配置态，已有专门引导页）。

## 验收

- `npx vitest run` 全绿，新增 3 个测试文件。
- `npm run typecheck` 无新增错误。
- ApiKeys 加载失败时页面只出现一个 `ErrorState`，无并列空表格。

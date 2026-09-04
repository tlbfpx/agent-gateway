# Round 10 B-2 · 全局错误处理与 ErrorState 兜底

## 背景

Round 10 评审附录 B-2 指出前端错误处理三处缺陷：

1. **错误信息易丢失** —— fetch 失败仅 `message.error` toast，3 秒自动消失。
   用户切走 tab / 未及时看屏幕即完全错过，事后无处追溯（通知中心里没有）。
2. **无路由级整页兜底** —— `AppShell` 内层 `ErrorBoundary scope="route"` 只在
   *渲染期 throw* 时生效；异步 fetch 失败（Promise reject）不触发错误边界，
   页面表现为表格区空白 + 一闪而过的 toast。
3. **ApiKeys 列表 error/table 双渲染** —— `ApiKeys/List.tsx:393` 的
   `{error && <ErrorState .../>}` 与 `:527` 的 `<Table>` 是**并列**关系，
   加载失败时同时出现「加载失败」红框和一个空表格，语义矛盾。
   （对照 `Models/List.tsx:413` 用的是三元 XOR，是正确写法。）

## 目标

- 错误进入**常驻**通知中心（已有 `useNotifications` + `NotificationCenter`），
  可事后回看、可标已读、可跳转，不再依赖 3 秒 toast。
- 路由级异常有整页 `ErrorState` 兜底，并能原地「重试」而非只能刷新整页。
- 修复 ApiKeys 双渲染，三个列表页错误呈现口径统一为 **XOR**。

## 方案概要

| 变更 | 文件 | 说明 |
|------|------|------|
| 新增 `QueryErrorBoundary` | `src/components/framework/QueryErrorBoundary.tsx` | class 组件，`componentDidCatch` + 整页 `ErrorState` + 重试重置 |
| 新增 `notifyError` | `src/lib/request.ts` | 错误 → 常驻通知中心 + `message.error` 兜底；同错误 5 分钟去重 |
| AppShell 接线 | `src/layouts/AppShell.tsx` | route 层 `ErrorBoundary` 换成 `QueryErrorBoundary` |
| 三个列表页 | `Models/List.tsx`、`ApiKeys/List.tsx`、`Traces.tsx` | catch 内统一调 `notifyError`；ApiKeys 改 XOR |

**不新建 `NotificationCenter.tsx`** —— 仓库已有功能完整的实现
（`src/components/framework/NotificationCenter.tsx` + `hooks/useNotifications.ts`，
含 Popover/未读 Badge/去重/localStorage 持久化）。本 change 复用其
`addNotification({ dedupKey })` 能力，避免造第二套通知栈。

## 非目标

- 不改造其他页面的错误处理（Round 10 后续批次统一迁移）。
- 不引入 react-query / SWR —— 命名沿用 `QueryErrorBoundary` 仅表意「数据查询错误边界」。
- 不动 `ErrorBoundary.tsx` 现有实现（app 层继续用它做最外层白屏兜底）。

## 影响面

- 用户可感知：加载失败时铃铛角标 +1，错误常驻可回看；ApiKeys 不再空表格叠红框。
- 回归风险：`notifyError` 在 `request.ts`（无 React 依赖）中通过动态引用调用
  antd `message`，需保证 SSR/测试环境不炸。

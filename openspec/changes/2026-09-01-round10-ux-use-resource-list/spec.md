# Spec: useResourceList（可测试条款）

#### GW-R10-URL-001 Hook 基础行为
**MUST**：`useResourceList({ fetcher })` 挂载时调用一次 fetcher；resolve 成功时返回 `{ data, loading: false, error: null, isEmpty: data.length === 0, reload }`。
**测试**：`useResourceList.test.ts > success path`。

#### GW-R10-URL-002 错误回调
**MUST**：fetcher throw 时，hook 把 `error` 置为 Error 实例、调用 `onError`（未传则调 `message.error(errorMessage ?? err.message)`）、返回 `{ error, loading: false, isEmpty: true }`。
**测试**：`useResourceList.test.ts > error path`。

#### GW-R10-URL-003 deps 切换自动重载
**MUST**：传入 `deps: [keyword]` 时，keyword 变化触发 fetcher 重新调用；初始挂载也调用一次。
**测试**：`useResourceList.test.ts > deps change`。

#### GW-R10-URL-004 手动 reload
**MUST**：调用 `reload()` 后会再次触发 fetcher 调用（用于重试按钮）；不改变 `deps`。
**测试**：`useResourceList.test.ts > manual reload`。

#### GW-R10-URL-005 emptyCheck 自定义
**MUST**：传入 `emptyCheck: item => !item.__empty` 时，hook 把 `__empty:true` 占位项视为无效；过滤后剩余 0 条则 `isEmpty=true`，否则 `isEmpty=false`（兼容后端占位场景）。
**测试**：`useResourceList.test.ts > emptyCheck override`。

#### GW-R10-URL-006 页面迁移覆盖
**MUST**：`Models/List.tsx`、`ApiKeys/List.tsx`、`Roles/List.tsx`、`Webhooks.tsx`、`Agents.tsx` 5 个页面不再包含 `setLoading(true)` / `try {` / `finally { setLoading(false)` 的传统模板，统一改用 `useResourceList`。
**测试**：代码 grep 验证 5 个页面已迁移；`vitest run` 全过。

#### GW-R10-URL-007 竞态安全
**MUST**：deps 快速切换 2 次，旧请求晚返回不会覆盖新请求的 `data`（避免基于脏数据操作）。
**测试**：`useResourceList.test.ts > stale request guard`。

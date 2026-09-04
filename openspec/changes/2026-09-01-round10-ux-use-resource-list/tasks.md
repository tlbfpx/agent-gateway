# Tasks: useResourceList

## 1. Hook 实现

- [ ] **T1** 新建 `agent-gateway-ui/src/hooks/useResourceList.ts`
  - 导出 `ResourceListOptions<T>` / `ResourceListResult<T>` / `useResourceList<T>`
  - `useState + useEffect + useCallback` 三件套
  - 默认 `onError` 用 `message.error(errorMessage ?? err.message)`
  - 内部 `cancelled` 标志位 + cleanup 防竞态

## 2. Hook 单元测试

- [ ] **T2** 新建 `agent-gateway-ui/src/hooks/useResourceList.test.ts`
  - 成功路径 / 失败路径 / deps 切换 / reload / emptyCheck / 竞态安全 6 个用例

## 3. 页面迁移（5 个，全部改）

- [ ] **T3** `Models/List.tsx` —— 删除 `load() / setLoading / setError / setModels` 四件套，改用 `useResourceList`
- [ ] **T4** `ApiKeys/List.tsx` —— 同上；保留 RBAC preview 的独立 useEffect
- [ ] **T5** `Roles/List.tsx` —— 同上；保留下拉数据源（models/agents）的独立 useEffect
- [ ] **T6** `Webhooks.tsx` —— 主体三列表（subs/dls/history）走 `Promise.allSettled`，用 `useResourceList` 替代内层 setter + finally
- [ ] **T7** `Agents.tsx` —— `useResourceList` 返回 `{items: Agent[], total: 0}`；保留 drawer / test

## 4. 验证

- [ ] **T8** `npm test`（vitest run）—— hook 测试 + 现有测试全过
- [ ] **T9** `npm run typecheck` —— TS 严格模式无错
- [ ] **T10** 行数对比：`git diff --stat HEAD agent-gateway-ui/src` —— 5 个页面合计减少，hook+测试新增

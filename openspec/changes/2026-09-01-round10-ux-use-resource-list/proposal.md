# Proposal: useResourceList hook 统一列表加载/错误/重试模板

> **状态**：Round 10 UX 增量，前端单一改动；无后端/契约变更
> **来源**：Round 7 报告 §6 候选 — 重复代码收敛（5 个 List 页面共享 fetch+loading+error 模板约 80% 重复）

## 动机

`Models/List.tsx` / `ApiKeys/List.tsx` / `Roles/List.tsx` / `Webhooks.tsx`（主体）/ `Agents.tsx`（主体）5 个页面各自粘贴：

```
const [data, setData]     = useState<T[]>([]);
const [loading, setLoading] = useState(false);
const [error, setError]     = useState<string>('');
const load = async () => { setLoading(true); try { setData(await fetcher()); setError(''); }
                           catch (e) { setData([]); setError(e?.message ?? '...'); }
                           finally { setLoading(false); } };
useEffect(() => { load(); }, []);
```

→ 列表数据加载 / 错误展示 / 重试按钮 / 空状态判断 4 个职责耦合在每个页面里，每次新页面都得抄一遍。

5 处复制约 280 行模板代码（×5）+ 易错的 `setLoading(true)` / `finally setLoading(false)` / 竞态（deps 切换时旧请求覆盖新请求）等问题。

## What

### 新 Hook `agent-gateway-ui/src/hooks/useResourceList.ts`

统一封装：`fetcher / deps / errorMessage / onError / emptyCheck` 5 字段，返回 `{ data, loading, error, reload, isEmpty }`。

行为约束：
- 默认挂载 + deps 变更自动重载
- 错误捕获 → setError + 触发 `onError`（默认 `message.error(errorMessage ?? err.message)`）
- 暴露 `reload()` 给重试按钮
- `isEmpty` 用 `emptyCheck` 或默认 `data.length === 0`

### 迁移 5 个页面

| 页面 | fetcher | 保留的特殊逻辑 |
|------|---------|---------------|
| `Models/List.tsx` | `() => listModels()` | Drawer / 灰度对比 / 导出 |
| `ApiKeys/List.tsx` | `() => listApiKeys()` | RBAC preview（独立 useEffect） |
| `Roles/List.tsx` | `() => listRoles()` | 下拉数据源（独立 useEffect） |
| `Webhooks.tsx` | 主体 `() => Promise.allSettled([listWebhooks()...])` 派生 `subs/dls/history` | dead-letter redeliver / history |
| `Agents.tsx` | 主体 `() => listRegisteredAgents(query)` → `{items, total}` | Drawer / 测试连通 |

仅替换列表 fetch + loading/error 三件套为 `useResourceList`；页面特性（Drawer / 表单 / 操作列）保留。

## Non-goals

- **不改 fetch 库**（保持 `apiClient` / 各模块 `lib/api/*`）
- **不改 UI 库**（仍用 antd Table + ErrorState）
- **不分页 hook 化**（pagination 走 `query.page/pageSize` 透传）
- **不动空状态文案 / CTA**（迁完后保留页面原 `description`）
- **不动测试覆盖范围外的页面**

## 验收

- 5 个页面里 `setLoading(true) / try/catch / finally setLoading(false)` 模式全部消失
- `vitest run` 全绿（含新增 `useResourceList.test.ts`）
- 行数：hook + 测试 ≈ +180 行；5 个页面合计 ≈ -80 行模板代码
- 类型严格（TypeScript `strict: true`，无 `any` 滥用）

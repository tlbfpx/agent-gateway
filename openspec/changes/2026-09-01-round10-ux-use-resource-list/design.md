# Design: useResourceList API

## 1. Hook 签名

```ts
export interface ResourceListOptions<T> {
  /** 拉数据的副作用函数；throw 即视为错误 */
  fetcher: () => Promise<T[]>;
  /** 依赖数组，任意一项变化触发自动重载；不传 = 只在挂载时拉一次 */
  deps?: ReadonlyArray<unknown>;
  /** 默认错误提示文案（fallback 当 fetcher 没附 message 时） */
  errorMessage?: string;
  /** 自定义错误回调；不传则用 antd `message.error` */
  onError?: (err: Error) => void;
  /**
   * 空判定：默认 `data.length === 0`
   * 兼容后端用 `{ __empty: true }` 占位的场景（项目里出现过）
   */
  emptyCheck?: (item: T) => boolean;
}

export interface ResourceListResult<T> {
  data: T[];
  loading: boolean;
  error: Error | null;
  isEmpty: boolean;
  reload: () => void;
}

export function useResourceList<T>(opts: ResourceListOptions<T>): ResourceListResult<T>;
```

## 2. 内部实现要点

- `useState` × 3：`data` / `loading` / `error`
- `useEffect` 监听 `[fetcher, ...deps]` —— fetcher 直接放 deps 会因闭包变更无限重渲染；因此把 fetcher 调用包成 `useCallback`
- 用 `cancelled` 标志位（每次 effect 重置）+ `useEffect` 清理函数处理 **竞态**：deps 切换时旧请求即使晚返回也不会覆盖 `data`
- 默认 `onError` 用 `antd` 的 `message.error` —— 与 5 个页面前行为一致
- `reload()` = 在 deps 里加一个 `nonce` 触发自动 effect 链；用 `useState(nonce) + useCallback(n => setNonce(n+1), [])`

## 3. 错误处理策略

| 场景 | 行为 |
|------|------|
| fetcher resolve | `data = result, error = null, loading = false` |
| fetcher reject | `data = []（保留旧值 if user wants paging）, error = err, loading = false, onError(err)` |
| 取消的旧请求 | effect cleanup 把 `cancelled=true`，避免 setState on unmounted |
| 用户点 reload 按钮 | 同 deps 变化路径，但触发 nonce 翻转 |

数据是否清空？两个选择：
- **保守**：错误时不清空 `data`，让用户看到上次成功的结果（适合监控/审计场景）
- **激进**：错误时 `data=[]`（避免基于脏数据操作）

**选择激进清空**——5 个原始页面都用 `setData([])`，行为对齐；同时 `ErrorState` 已经显示，Table 不出现会引起混乱。

## 4. 测试方案（`src/hooks/useResourceList.test.ts`）

| 用例 | 断言 |
|------|------|
| 成功路径 | `fetcher` resolve `[a,b]` → `data=[a,b], loading=false, error=null, isEmpty=false` |
| 失败路径 | `fetcher` throw → `error=Error('xxx'), loading=false, isEmpty=true` + `onError` 被调用一次 |
| deps 切换 | 初始拉一次；改 deps 后再拉一次；验证 fetcher 调用计数 |
| reload 手动 | `reload()` 后 fetcher 再次调用 |
| emptyCheck 自定义 | `[]` 视为空；`[{__empty:true}]` 视为非空（兼容后端占位） |
| 竞态 | deps 快速切换 2 次；旧请求晚返回不会覆盖 `data` |

Mock：fetcher 用 `vi.fn()`，避免真实网络。`renderHook` + `act` 来自 `@testing-library/react`（已在 devDeps）。

## 5. 迁移策略

对每个页面：
1. 删除 `useState<loading> / useState<error> / useState<data>`
2. 删除 `const load = async () => { ... }`
3. 把 `useEffect(() => { load() }, [])` 替换为 hook 内部自动处理
4. 替换页面中所有 `load()` 调用为 `reload()`
5. 替换 `{error ? <ErrorState/> : <Table/>}` 条件渲染为 `<Table/>` + 触发的 `message.error` toast

保留：
- `Table columns` 定义
- `Drawer / Modal / Form` UI
- 操作列（删除 / 编辑 / Topup / Test / Reload）
- `useAutoOpenCreate`、`usePermission` 等其他 hook
- 各页面独有的 `useState`（如 `Models` 的 `grayscaleOpen`）

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| deps 缺漏 → 该重载没重载 | 编码规范：把搜索/过滤 state 显式列入 |
| fetch 错误静默丢失 | 默认 `message.error` 弹 toast |
| unmount 后 setState 警告 | `cancelled` 标记 |
| TypeScript 推导失败（T→T[]→map） | hook 泛型 `<T>`，调用方显式标或利用类型推断 |

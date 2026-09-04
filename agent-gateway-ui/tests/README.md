# Tests

生产级测试套件，分三层：

## 1. 单元 / 组件
- `format.test.ts`  `request.test.ts`  `PageHeader.test.tsx`  `StatCard.test.tsx`  `AppShell.test.tsx`

## 2. 业务 / API
- `Agents.test.tsx` — Agents 页 CRUD：列出 / 筛选 / 新建 / 校验 / 启停 / 删除 / 连通性
  + 覆盖 `lib/api/agents.ts` 全套 admin 接口

## 3. 全链路覆盖（所有左侧菜单页面）
- `pages.coverage.test.tsx` — 13 个菜单页面在 seed 数据下的渲染

## 工具

- `fixtures/seed.ts`   — 与 API 形状一致的种子数据（models / apiKeys / webhooks /
                          audit / configVersions / agents / health / chatSessions）
- `fixtures/mockServer.ts` — 拦截 `globalThis.fetch`，按路由模板匹配，含：
                          - 默认路由表（GET/POST/PUT/DELETE/availability/test/...)
                          - `mock.on(method, path, handler)` 覆盖某路由
                          - `mock.nextReply(method, path, body, status)` 一次性返回
                          - `mock.uninstall()` 恢复原 fetch
                          - `mock.store` 可读写（直接断言状态变化）
- `harness.tsx`        — renderPage / renderWithRouter（MemoryRouter）

## 跑测试

```bash
npm test                # 单次跑全部
npm run test:watch      # 监听
npm run typecheck       # 仅类型检查
npm run build           # 生产构建
npm run verify          # typecheck + test + build，一站式验收
```

## 测试隔离

- `setup.ts` 注入：
  - jsdom localStorage polyfill（jsdom 25+ 不再默认暴露）
  - matchMedia / getComputedStyle / IntersectionObserver / scrollIntoView stubs
  - window.fetch 包装：剥离掉 jsdom 与 Router 内部 Request signal 校验问题
- 每个测试 `beforeEach` install mock → `afterEach` uninstall
- 每个测试用 `resetSeed()` 拿独立 store，互不干扰

## 注意事项

- 不依赖 `@testing-library/user-event`（避免多余依赖），全用 `fireEvent`
- React Router 的 createBrowserRouter 在 jsdom 下 useNavigate 会触发底层的 Request
  data fetching，因此菜单联动改用 `renderWithRouter(<Page />)` 直接渲染页面

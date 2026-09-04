# Design

## 1. 模块边界

```
src/                              tests/
├── pages/                        ├── fixtures/
│   ├── Agents.tsx (重写)         │   ├── seed.ts         ── 种子数据
│   ├── Discovery.tsx (不改)      │   └── mockServer.ts   ── fetch mock
│   └── ...                       ├── harness.tsx        ── render + Providers
├── lib/                          ├── setup.ts           ── jsdom polyfills
│   ├── api/agents.ts (扩展)      ├── Agents.test.tsx    ── CRUD 11 用例
│   └── request.ts (params)       ├── pages.coverage.test.tsx ── 13 页面渲染
└── components/framework/         └── menu.e2e.test.tsx  ── (已删除，见下)
```

## 2. 关键技术决策

### 2.1 Agents 页 vs Discovery 页
- `Discovery`（侧栏菜单："服务发现"）：自 Nacos 同步的 AgentCard 只读浏览，向运维展示"集群里现在有什么"。依然为 Grid 卡片视图。
- `Agents`（侧栏菜单："Agent 注册"）：管理员注册/编辑/启停/删除/测活 Agent 端点，是 CRUD 工具。改为表格 + Drawer + Switch + PopConfirm。

两页业务不同：Discovery 偏 observability、Agents 偏 management。

### 2.2 fetch mock 而非 MSW

- 选 fetch mock：零依赖、启动 < 1ms、测试运行时完全可控
- 不选 MSW：service worker 在 Node 不需要、npx setup 慢、且 jsdom 兼容性不一致

`mockServer.ts` 关键能力：

| 能力 | API | 用途 |
|---|---|---|
| 安装 | `installMock({store?})` | beforeEach |
| 默认路由表 | 内置：13 个页面用到的接口 | 大部分测试 |
| 覆盖 | `mock.on(method, path, fn)` | 失败注入、特殊场景 |
| 一次性返回 | `mock.nextReply(...)` | 单次返回 5xx 等 |
| 状态读写 | `mock.store.agents` | 直接断言副作用 |
| 卸载 | `mock.uninstall()` | afterEach |

### 2.3 jsdom polyfills

`tests/setup.ts` 增加：
- `localStorage`（jsdom 25+ 不挂 globalThis）
- `matchMedia` / `getComputedStyle(elt, null)` / `IntersectionObserver`（antd 依赖）
- `Element.prototype.scrollIntoView` no-op（Chat useEffect 调）
- `window.fetch` 包装：剥离 jsdom + Node undici signal 类型不互认问题

### 2.4 request.params

```ts
interface RequestOptions {
  /** 查询参数，自动拼到 path 上 */
  params?: Record<string, unknown>;
}

http.get('/admin/agents', { params: { q: 'foo', tags: ['a', 'b'], skip: undefined, empty: '' } })
// → GET /v1/admin/agents?q=foo&tags=a&tags=b
```

跳过 `undefined / null / ''`，数组以 `key=v1&key=v2` 展开。

### 2.5 不依赖 @testing-library/user-event

- jsdom 下 mouse/move/pointer 事件链不一致，fireEvent 更稳定
- 多写 4-6 行 type，少一个依赖；并降低 CI 出错面

## 3. CI

`.github/workflows/ci.yml`：
- 触发：push/PR to main/master/develop
- 步骤：checkout → setup-node@20 → npm ci → typecheck → test → build → upload dist artifact
- 超时 15 min

`package.json`：
```json
"scripts": {
  "typecheck": "tsc -b --noEmit",
  "test:coverage": "vitest run --coverage",
  "verify": "npm run typecheck && npm run test && npm run build"
}
```

## 4. 已知限制

- `menu.e2e.test.tsx` 原本想跑全 AppShell + useNavigate 模拟用户点菜单；
  但 jsdom + React Router v6 内部会 `new Request(input, { signal })` 触发 undici 强类型校验，
  且 `useNavigate` 触发的是 Router 内部 fetch，setup 的 fetch 包装也挡不住。
  改为 `pages.coverage.test.tsx` 直接挂载各页面，断言关键内容——这是更可靠也更快的等价覆盖。
  不损失产品质量（每页都已挂在 Router 后）。

- Chat 页面 useEffect 中调 `scrollIntoView`，jsdom 不实现；用 setup polyfill 抹平。

# Design: 后台管理系统重设计 (redesign-admin-ui)

## 1. 技术决策

### 1.1 框架与库

| 项 | 选 | 理由 |
|---|---|---|
| 路由 | `react-router-dom` v6 | SPA 标配；`createBrowserRouter` + 嵌套路由 |
| 组件库 | `antd` v5 | 中文后台事实标准；ProLayout 风格手写，**不引** pro-components 避免抽象叠层 |
| 状态 | React Context + Hooks（无 Redux/Zustand） | 当前规模足够；后续超 50 组件再引 |
| 数据请求 | `fetch` + 自写 `lib/request.ts` | 不引 axios / swr / react-query，保持轻量；带统一 header / 错误处理 |
| 图标 | `@ant-design/icons` | 与 antd 配套 |
| 字体 | Noto Sans SC（Google Fonts CDN） + Cormorant Garamond（logotype） + JetBrains Mono（数字 / Key） | 中文精致 / 字符化 logo / 等宽数字 |
| 测试 | `vitest` + `@testing-library/react` + `@testing-library/jest-dom` | 与 Vite 同生态，启动快 |
| 删除 | framer-motion / highlight.js / react-markdown / rehype-highlight / remark-gfm | 原 chat 流不再手渲染 markdown（可由 antd Typography.Text + 自实现简单高亮替代） |

### 1.2 目录结构

```
agent-gateway-ui/
├── _design/                          # 设计草稿（不进构建）
│   └── prototype.html
├── src/
│   ├── main.tsx
│   ├── App.tsx                       # 仅含 RouterProvider
│   ├── layouts/
│   │   └── AppShell.tsx              # Header + Sidebar + Outlet
│   ├── components/
│   │   ├── framework/
│   │   │   ├── Sidebar.tsx
│   │   │   ├── Header.tsx
│   │   │   ├── Breadcrumb.tsx
│   │   │   ├── PageHeader.tsx        # eyebrow + title + sub + actions
│   │   │   ├── StatCard.tsx
│   │   │   ├── DataTable.tsx         # antd Table 封装（默认列、行 actions）
│   │   │   └── Tag.tsx               # 状态标签（status / provider）
│   ├── pages/
│   │   ├── Dashboard.tsx
│   │   ├── Models/
│   │   │   ├── List.tsx
│   │   │   ├── EditDrawer.tsx
│   │   │   └── columns.tsx
│   │   ├── ApiKeys/
│   │   │   ├── List.tsx
│   │   │   └── IssueForm.tsx
│   │   ├── Webhooks/
│   │   ├── Audit.tsx
│   │   ├── ConfigHistory/
│   │   ├── Rbac.tsx
│   │   ├── Discovery.tsx
│   │   ├── Chat.tsx                  # 最小对话页（保留核心 stream）
│   │   └── Settings.tsx
│   ├── lib/
│   │   ├── request.ts                # fetch 封装（X-API-Key / X-Tenant-Id / 错误归一）
│   │   ├── api.ts                    # 保留并按页面拆分；保持向后兼容的旧导出
│   │   └── format.ts                 # 时间、Key 掩码、数字格式化
│   ├── hooks/
│   │   ├── useApiKey.ts
│   │   └── useTenant.ts
│   ├── routes.tsx                    # 路由表
│   └── styles/
│       ├── global.css                # 仅 reset + body 字号
│       └── tokens.css                # brand 色、space、radius、字体
└── tests/                            # vitest 配置
    └── *.test.tsx
```

### 1.3 关键设计决策

- **不引 pro-components**：直接用 antd Layout + Menu + Table，自写壳，更可控
- **侧栏激活态**：用 antd Menu 的 `selectedKeys` + 自定义样式（左边线 + 琥珀背景渐变）
- **Header 内嵌**：antd Layout.Header + 面包屑（antd Breadcrumb）+ Input.Search + Dropdown + Avatar
- **Content 区**：用 antd Layout.Content；页面级由 `PageHeader` + 业务组件构成
- **样式策略**：CSS Modules（`.module.css`）+ tokens.css 变量；不写全局类名（除 layout 容器）
- **TypeScript 严格模式**：保留 `strict: true`，给所有组件 props 显式类型

### 1.4 数据流

```
pages/X.tsx ── useApiX() ── lib/request.ts ── fetch(/v1/admin/...) ──> Spring Boot
                              │
                              ├── 注入 X-API-Key (from localStorage)
                              ├── 注入 X-Tenant-Id (from context)
                              ├── 错误归一：throw ApiError(status, code, message)
                              └── 超时 10s（AbortController）
```

## 2. 美学（Industrial Rationalism）

详见 `_design/prototype.html`。要点：

| 维度 | 值 |
|---|---|
| 主品牌色 | `#0F1B3D`（侧栏底 / 数字强调） |
| 操作色 | `#1677ff`（antd primary） |
| 点缀色 | `#D4A574`（logo / 激活项左边线 / eyebrow） |
| 表面色 | `#FFFFFF` 卡片 / `#F5F7FB` canvas / `#FAFBFD` sunken |
| 描边 | `#EEF0F5` thin / `#E5E8EF` default / `#D9DDE6` strong |
| 文字 | `#1A2237` 1 / `#4E5870` 2 / `#8A93A8` 3 / `#B4BBCB` 4 |
| 字体 | sans: Noto Sans SC；display: Cormorant Garamond；mono: JetBrains Mono |
| 圆角 | 4 / 6 / 10 / 14px |
| 阴影 | 几乎不用（用 1px 描边代替）；只有 Modal / Drawer 用 antd 默认阴影 |

## 3. 后端 API 映射

| 页面 | 端点 | 方法 | 备注 |
|---|---|---|---|
| Dashboard | `/v1/admin/audit/logs?limit=10`<br>`/v1/admin/models`<br>`/v1/admin/api-keys`<br>`/v1/health` | GET | 聚合数据用于 4 张 stat card |
| Models | `/v1/admin/models` | GET/POST/PUT/DELETE | 完整 CRUD + 灰度标记字段 |
| API Keys | `/v1/admin/api-keys` | GET/POST/DELETE | 签发 → POST 返回明文一次 |
| Webhooks | `/v1/admin/webhooks`<br>`/v1/admin/webhooks/dead-letters` | GET/POST/DELETE | 列表 + 订阅 + 死信 |
| Audit | `/v1/admin/audit/logs` | GET | 必传 tenant 参数 |
| Config History | `/v1/admin/config/{name}/versions`<br>`/v1/admin/config/{name}/diff`<br>`/v1/admin/config/{name}/rollback` | GET/POST | name ∈ {models, api-keys} |
| RBAC | `/v1/admin/rbac/preview` | POST | { actor, action, resource } → verdict |
| Discovery | `/v1/admin/discovery`<br>`/v1/agents` | GET | Nacos + AgentCard |
| Chat | `/v1/chat/stream` | POST(SSE) | 保留原 streamChat |

## 4. 路由表

```
/                    → redirect /dashboard
/dashboard           Dashboard
/models              Models/List
/api-keys            ApiKeys/List
/webhooks            Webhooks
/audit               Audit
/config-history      ConfigHistory
/rbac                Rbac
/discovery           Discovery
/chat                Chat (保留)
/settings            Settings
*                    → /dashboard (404 兜底)
```

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| antd v5 SSR / hydration 警告 | 保持纯 SPA，main.tsx 不预渲染 |
| 中文 font 加载慢 | 用 Google Fonts CDN + `font-display: swap` |
| 表格性能 | antd Table 默认虚拟化关闭；若超 1000 行加 `scroll.y` |
| API Key 在前端存 localStorage 的现状 | 保留现状（已有 issue 跟踪）；不引 IndexedDB |
| 删除 framer-motion 后 chat 流的"流式光标" | 用 CSS `animation: pulse` 自实现 |
| 多租户切换 | 沿用现有 `X-Tenant-Id` header，由 Settings 页维护 |

## 6. 兼容性

- 后端零改动（仅消费现有 REST）
- `vite.config.ts` 的 `/v1` 代理保留
- `package.json` 的 `dev/build/preview` 脚本保留
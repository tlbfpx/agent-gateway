# Tasks: 后台管理系统重设计 (redesign-admin-ui)

> 任务清单视图。详细 step 留待 `plans/2026-XX-XX-redesign-admin-ui.md`（阶段三启动时由 writing-plans 产出）。
> 本文件给出**阶段切分**与**验收门**，作为阶段三的导航。

---

## 阶段切分

| 阶段 | 内容 | 验收门 |
|---|---|---|
| **A. 基础设施** | 引入 antd + 路由 + 测试框架；清理旧依赖；写 tokens.css；写 AppShell | `npm run dev` 起得来，AppShell 渲染三个空页面 |
| **B. 数据层** | 写 `lib/request.ts`；迁移 `lib/api.ts`；为每个页面写 hooks | 单测覆盖 request.ts；mock 数据能渲染列表 |
| **C. 页面** | 10 个页面逐个实现，按 Dashboard → Models → ApiKeys → 其它 → Chat → Settings | 每个页面单测通过；视觉与 prototype.html 一致 |
| **D. 联调** | 接后端 `/v1/admin/*`；填真实数据；走查三页截图 | E2E：登录 → 创建模型 → 创建 Key → 查看审计 |
| **E. 归档** | 对照 spec.md；测试覆盖率检查；build 通过 | 完成 §6 审查清单 |

---

## 任务列表（按阶段）

### 阶段 A：基础设施

- [ ] **A.1** 更新 `package.json`：移除 framer-motion / highlight.js / react-markdown / rehype-highlight / remark-gfm
- [ ] **A.2** 添加依赖：`antd` v5、`@ant-design/icons`、`react-router-dom` v6
- [ ] **A.3** 添加 dev 依赖：`vitest`、`@testing-library/react`、`@testing-library/jest-dom`、`jsdom`
- [ ] **A.4** 添加 `vitest.config.ts`，在 `vite.config.ts` 中合并
- [ ] **A.5** 重写 `src/styles/tokens.css`：brand 色 / 字体 / 间距（见 design.md §2）
- [ ] **A.6** 重写 `src/styles/global.css`：仅 reset + body
- [ ] **A.7** 新建 `src/routes.tsx`：10 个路由（见 design.md §4）
- [ ] **A.8** 新建 `src/layouts/AppShell.tsx`：Layout + Sidebar + Header + Outlet
- [ ] **A.9** 新建 `src/components/framework/Sidebar.tsx`：分组 Menu + 折叠 + 激活态样式
- [ ] **A.10** 新建 `src/components/framework/Header.tsx`：面包屑 + 搜索 + 通知 + 用户菜单
- [ ] **A.11** 新建 `src/components/framework/PageHeader.tsx`：eyebrow + title + sub + actions
- [ ] **A.12** 新建 `src/App.tsx`：仅含 `RouterProvider`
- [ ] **A.13** 删除 `src/components/framework/TopBar.tsx` / `NavRail.tsx` / `CommandPalette.tsx`
- [ ] **A.14** 删除 `src/components/sidebar/SessionList.tsx` / `chat/*.tsx`
- [ ] **A.15** 删 `index.html` boot-error（不再需要 chat 流容错）

### 阶段 B：数据层

- [ ] **B.1** 新建 `src/lib/request.ts`：fetch 封装（X-API-Key / X-Tenant-Id / 错误归一 / 超时）
- [ ] **B.2** 新建 `src/lib/format.ts`：时间 / Key 掩码 / 数字格式化
- [ ] **B.3** 拆分 `src/lib/api.ts` 为 `src/lib/api/{models,apiKeys,webhooks,audit,config,rbac,discovery,chat,sessions}.ts`
- [ ] **B.4** 新建 `src/hooks/useApiKey.ts` / `useTenant.ts`
- [ ] **B.5** 单测：`request.test.ts`（成功 / 401 / 500 / 超时）

### 阶段 C：页面（10 个）

- [ ] **C.1** Dashboard：4 张 StatCard + 14 天柱状图（纯 CSS bars）+ 事件流 + 系统状态 + Top 模型 + 租户分布
- [ ] **C.2** Models/List：Tabs + 筛选条 + Table + 新建/编辑 Drawer
- [ ] **C.3** Models/columns.tsx：列定义 + Tag 状态映射
- [ ] **C.4** ApiKeys/List：左侧表单 + 右侧最近表 + 底部 RBAC 预览
- [ ] **C.5** Webhooks：列表 + 新增订阅表单 + 死信折叠区
- [ ] **C.6** Audit：租户下拉 + 时间范围 + Table
- [ ] **C.7** ConfigHistory：name 下拉 + 版本表 + diff 视图 + 回滚
- [ ] **C.8** Rbac：四要素输入 + 预览 verdict
- [ ] **C.9** Discovery：AgentCard 列表
- [ ] **C.10** Chat：最小实现（沿用 streamChat）+ 流式光标（CSS animation）
- [ ] **C.11** Settings：API Key + Tenant + 清除

### 阶段 D：联调

- [ ] **D.1** 接 `/v1/admin/models`：Models 页 CRUD 真数据
- [ ] **D.2** 接 `/v1/admin/api-keys`：签发 / 撤销真数据
- [ ] **D.3** 接 `/v1/admin/webhooks` + `/dead-letters`
- [ ] **D.4** 接 `/v1/admin/audit/logs`
- [ ] **D.5** 接 `/v1/admin/config/{name}/versions` + `/diff` + `/rollback`
- [ ] **D.6** 接 `/v1/admin/rbac/preview`
- [ ] **D.7** 接 `/v1/agents` + `/v1/models`（Chat 页）
- [ ] **D.8** 走查三页截图：Dashboard / Models / ApiKeys；归档到 `docs/superpowers/screenshots/`

### 阶段 E：归档

- [ ] **E.1** 对照 spec.md 10 节 SHALL 条款逐条核验，填 §6 审查清单
- [ ] **E.2** `npm run test -- --coverage` 覆盖率 ≥ 80%
- [ ] **E.3** `npm run build` 通过；产物在 `dist/`
- [ ] **E.4** 标记 OpenSpec change 为完成
- [ ] **E.5** commit（按 backend-architect/developer 规范；前端任务单独 commit）

---

## 详细 step

详细 step 留待 `docs/superpowers/plans/2026-XX-XX-redesign-admin-ui.md`（writing-plans skill 产出）。

---

## 关联

- proposal: `openspec/changes/redesign-admin-ui/proposal.md`
- design: `openspec/changes/redesign-admin-ui/design.md`
- spec: `openspec/changes/redesign-admin-ui/spec.md`
- 设计草稿: `agent-gateway-ui/_design/prototype.html`
# Spec: 后台管理系统重设计 (redesign-admin-ui)

> 行为规格，可测试条款。**SHALL** 标记为强制。

---

## 1. 应用启动与路由

### S1.1 默认入口

> 应用启动 SHALL 自动跳转至 `/dashboard`，未匹配路由 SHALL 重定向到 `/dashboard`。

### S1.2 路由表

> 系统 SHALL 提供以下路由与对应组件：

| Path | 组件 |
|---|---|
| `/dashboard` | `pages/Dashboard` |
| `/models` | `pages/Models/List` |
| `/api-keys` | `pages/ApiKeys/List` |
| `/webhooks` | `pages/Webhooks` |
| `/audit` | `pages/Audit` |
| `/config-history` | `pages/ConfigHistory` |
| `/rbac` | `pages/Rbac` |
| `/discovery` | `pages/Discovery` |
| `/chat` | `pages/Chat` |
| `/settings` | `pages/Settings` |

### S1.3 渲染壳

> 所有管理页面 SHALL 渲染于 `AppShell` 内（侧栏 + 顶栏 + 内容区）。

---

## 2. AppShell 布局

### S2.1 顶部 Header

> Header SHALL 高度 56px，白底（`#FFFFFF`），底边 1px 浅灰描边（`#EEF0F5`）。
> Header SHALL 自左向右包含：面包屑、全局搜索框（占位文字"搜索…"）、通知图标、用户菜单（头像 + 用户名 + 角色）。
> 面包屑 SHALL 反映当前路由的最后两级路径（如 `资源管理 / 模型管理`）。

### S2.2 左侧 Sidebar

> Sidebar 默认宽度 SHALL 为 240px，深色底（`#0F1B3D`）。
> Sidebar SHALL 提供折叠开关，折叠后宽度为 64px，仅显示图标。
> Sidebar SHALL 包含分组菜单（总览 / 资源管理 / 运营 / 应用），每组有标题小标题。
> 当前激活项 SHALL 显示：3px 琥珀色左边线（`#D4A574`）+ 浅琥珀背景渐变（`rgba(212,165,116,.10)` → transparent）。
> Sidebar 底部 SHALL 显示版本号（等宽）。

### S2.3 内容区

> 内容区内边距 SHALL 为 24px，背景为 canvas（`#F5F7FB`）。

---

## 3. PageHeader

### S3.1 结构

> 每个页面 SHALL 在顶部包含 `PageHeader` 组件，结构为：
  - eyebrow（小字 + 琥珀色 + 大写字母 + 字间距）
  - title（22px / 600 weight）
  - sub（13px / 第三级文字色）
  - 右侧 actions 区域（按钮组）

---

## 4. Dashboard 页

### S4.1 统计卡片

> Dashboard SHALL 展示 4 个统计卡片，分别显示：在线模型数、活跃 API Key 数、24h 请求数、Agent 注册数。
> 每张卡片 SHALL 显示数值（28px / 等宽字体 / `tabular-nums`）+ 趋势指示（▲ 绿色 / ▼ 红色）。

### S4.2 趋势图

> Dashboard SHALL 展示 14 天调用量柱状图，柱条 hover 时 SHALL 变为蓝色渐变。

### S4.3 状态区

> Dashboard SHALL 展示系统状态卡（Gateway / Nacos / Redis / LLM Provider），每项前 SHALL 有 8x8 圆点指示器（绿/黄/红）。

---

## 5. Models 页

### S5.1 Tabs

> 顶部 SHALL 有 4 个 Tabs：全部 / 启用 / 灰度 / 停用，每个 Tab 显示当前数量。

### S5.2 筛选条

> SHALL 包含搜索输入框（按 modelId / displayName）+ Provider 下拉 + 状态下拉 + 重置按钮。

### S5.3 表格列

> 表格 SHALL 包含列：模型、Provider、Endpoint、Context、状态、能力、操作。
> 模型列 SHALL 同时显示 displayName 和 modelId（mono 字体）。
> 状态列 SHALL 使用 Tag 组件，颜色：启用=绿、灰度=琥珀、停用=红。
> 操作列 SHALL 包含：编辑、灰度、删除（删除为红色 link button）。

### S5.4 CRUD

> 新建模型 SHALL 打开右侧 Drawer，保存后 SHALL 刷新表格。
> 编辑 SHALL 复用 Drawer，预填表单。
> 删除 SHALL 弹出 Popconfirm 二次确认。

---

## 6. API Keys 页

### S6.1 签发表单

> 左侧 SHALL 显示签发表单，必填字段：所属租户、授权模型；可选：限速 RPM、过期时间。

### S6.2 Key 列表

> 右侧 SHALL 显示最近签发的 Key，Key 预览 SHALL 用 mono 字体 + 掩码（如 `pk_live_••••3f8a`）。
> 撤销 SHALL 为红色 link button，弹出 Popconfirm 二次确认。

### S6.3 RBAC 预览

> 底部 SHALL 有跨租户授权预览卡，展示 actor/resource/action/verdict 四要素网格。

---

## 7. 其他页面（最小可用）

### S7.1 Webhooks

> SHALL 提供：当前订阅列表（URL + events）、新增订阅表单、删除按钮、查看死信队列入口。

### S7.2 Audit

> SHALL 提供：租户选择下拉、时间范围、刷新按钮、表格（eventId / actor / type / time / resource / action / result / detail）。

### S7.3 ConfigHistory

> SHALL 提供：name 下拉（models / api-keys）、版本列表表格、选中两个版本后展示 diff、选中单个版本可回滚。

### S7.4 RBAC

> SHALL 提供：四要素输入（actor / resource / action）+ 调用 `/v1/admin/rbac/preview` + 展示 verdict（允许=绿，拒绝=红）。

### S7.5 Discovery

> SHALL 提供：AgentCard 列表（name / description / skills / available 状态点）。

### S7.6 Chat

> SHALL 保留基本对话能力：模型下拉 + 输入框 + 流式输出（沿用 `streamChat`）。
> 工具调用 SHALL 用 chip 显示，3 个状态：调用中 / 完成 / 失败。

### S7.7 Settings

> SHALL 提供：API Key 输入（X-API-Key）、租户 ID 输入（X-Tenant-Id）、清除按钮、版本信息。

---

## 8. 设计系统

### S8.1 配色 token

> SHALL 使用以下 token（写于 `styles/tokens.css`）：

```css
--brand-deep: #0F1B3D;
--brand-amber: #D4A574;
--ant-primary: #1677ff;
--bg-canvas: #F5F7FB;
--bg-surface: #FFFFFF;
--bg-sunken: #FAFBFD;
--border-thin: #EEF0F5;
--text-1: #1A2237;
--text-2: #4E5870;
--text-3: #8A93A8;
```

### S8.2 字体

> 正文字体 SHALL 为 Noto Sans SC。
> Logo 字体 SHALL 为 Cormorant Garamond。
> Key / 数字 / 版本号 SHALL 为 JetBrains Mono。

### S8.3 Tag 颜色规范

> 状态 Tag SHALL 使用：启用=绿、灰度=琥珀、停用=红、测试=琥珀、已撤销=红；Provider Tag SHALL 使用蓝色描边。

---

## 9. 请求与错误

### S9.1 请求头

> 所有 `/v1/admin/*` 请求 SHALL 自动注入 `X-API-Key`（从 localStorage）和 `X-Tenant-Id`（从 Settings）。

### S9.2 错误处理

> 非 2xx 响应 SHALL 抛出 `ApiError(status, code, message)`，UI SHALL 通过 antd `message.error` 显示。
> 401 SHALL 清空 localStorage 中的 API Key 并跳转到 `/settings`。

---

## 10. 测试

### S10.1 单元测试

> 关键组件 SHALL 有测试：`AppShell` / `Sidebar` / `Header` / `PageHeader` / `pages/Models/List` / `lib/request.ts`。
> 关键模块覆盖率 SHALL ≥ 80%。

### S10.2 构建

> `npm run build` SHALL 成功无报错；`npm run dev` SHALL 启动并能在浏览器加载 `/dashboard`。
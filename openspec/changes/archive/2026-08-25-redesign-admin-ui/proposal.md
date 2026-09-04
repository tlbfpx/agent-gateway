# Proposal: 后台管理系统重设计 (redesign-admin-ui)

> **状态：✅ 已完成**（2026-08-25 归档）。功能由同期 `complete-left-menu-coverage` change 落地（21 个路由、AppShell、antd 设计系统、203 测试）。验证见 `VERIFICATION.md`。

## 变更概述

将 `agent-gateway-ui` 由"聊器风格"（framer-motion + 自渲染 chat 流）整体替换为**传统中文后台管理系统风格**：深色侧栏 + 白色顶栏 + 内容区三段式布局，引入 Ant Design 作为基础组件库，删除手写的聊器样式，规范化所有管理页面。

## What / 范围

### 做

- **布局壳（AppShell）**
  - 顶部 Header：面包屑 + 全局搜索（⌘K）+ 通知 + 用户菜单；高度 56px，白底，1px 浅灰下边线
  - 左侧 Sidebar：深海军蓝底色，分组菜单（总览 / 资源管理 / 运营 / 应用），可折叠（240px ↔ 64px），激活项以 3px 暖琥珀左边线标识
  - 主内容区：24px 内边距，白底卡片 + 1px 浅灰描边（不依赖重阴影）

- **页面路由（基于 react-router-dom v6）**
  - `/` → 重定向到 `/dashboard`
  - `/dashboard` 概览：统计卡片 + 14 天调用量柱状图 + 最近事件 + 系统状态 + Top 模型 + 租户分布
  - `/models` 模型管理：Tabs（全部/启用/灰度/停用）+ 筛选条 + 表格 CRUD + 分页
  - `/api-keys` API Key 管理：左侧签发表单 + 右侧最近签发表 + 跨租户授权预览
  - `/webhooks` Webhook 订阅：列表 + 新增订阅 + 死信队列
  - `/audit` 审计日志：按租户筛选 + 时间范围 + 表格
  - `/config-history` 配置历史：版本列表 + diff 预览 + 回滚
  - `/rbac` RBAC 预览：四要素（actor/resource/action/verdict）判定卡
  - `/discovery` 服务发现：AgentCard 列表 + 心跳状态
  - `/chat` 对话测试（保留）：最小实现，能调用 `/v1/chat/stream`
  - `/settings` 设置：API Key 维护 + 主题

- **设计系统**
  - 引入 `antd` v5（default theme 不动，覆盖 token）
  - 自定义 token：brand-deep `#0F1B3D`、brand-amber `#D4A574`、字体 Noto Sans SC + Cormorant Garamond + JetBrains Mono
  - 删除原 `tokens.css` 全部 chat 主题变量；删除 `framer-motion`、`highlight.js`、`react-markdown`、`rehype-highlight`、`remark-gfm` 依赖
  - 保留并精简 `lib/api.ts`：所有 admin REST 调用迁移到对应页面；新增 `lib/request.ts` 统一封装（带 X-API-Key / X-Tenant-Id）

- **测试**
  - Vitest + @testing-library/react 接入；新增 `AppShell.test.tsx`（壳渲染）/ `pages/Models.test.tsx`（CRUD 流程 mock）
  - 关键单元 ≥ 80% 覆盖
  - `npm run build` 通过；视觉走查截图三页（Dashboard/Models/ApiKeys）

### 不做（YAGNI / 后续）

- 多标签页（Tab Keep Alive）—— 留二期
- 国际化 i18n（仅中文）
- 暗色模式切换 —— 仅浅色
- WebSocket 实时推送 —— 仪表盘仍走 30s 轮询
- ProComponents（@ant-design/pro-components）—— Ant Design 原生 + 自写组件，避免再叠一层抽象
- 服务端渲染（SSR）—— Vite SPA

## 验收标准

1. 启动 `npm run dev` 后浏览器默认进入 `/dashboard`，布局为 240px 深色侧栏 + 56px 白色顶栏 + 24px 内边距内容区。
2. 侧栏 8 个一级菜单项，点击切换路由，激活态用琥珀左边线 + 浅琥珀背景。
3. 7 个管理页面均能渲染（mock 或真实数据），含空态、加载态、错误态。
4. `/chat` 仍能完成基本对话流（兼容原 `streamChat`）。
5. 单元测试套件通过；关键组件覆盖 ≥ 80%；`npm run build` 通过。
6. 视觉与 `_design/prototype.html` 一致（仅替换 ASCII icon 为 antd icons）。

## 关联文档

- 设计草稿：`agent-gateway-ui/_design/prototype.html`
- 项目规范：`AGENTS.md` §1-§8
- 后端 API：详见 `design.md` §3
- 原项目设计：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
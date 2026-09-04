# complete-left-menu-coverage

## What

完成 admin UI 左侧菜单所有 13 个菜单项的端到端覆盖，达生产级质量：

1. **补完 `Agents` 页**：从 Discovery 的 re-export 改为独立 CRUD（列表 + 筛选 + Drawer 新建/编辑 + 启停开关 + 连通性测试 + 删除确认）
2. **测试数据 seed**：每个页面都有可重用的种子数据，类型严格对齐 API 契约
3. **轻量级 fetch mock**：拦截 `globalThis.fetch`、支持路由模板匹配 + override + nextReply；零额外依赖
4. **全量 E2E 测试**：13 个菜单页面在 seed 下都能渲染、Agents CRUD 完整流程、Production HTTP 链路
5. **测试基础设施**：jsdom polyfills（localStorage / matchMedia / getComputedStyle / IntersectionObserver / scrollIntoView / AbortSignal 包装）
6. **`request.ts` 增强**：支持 `params` 查询串序列化（数组以 `key=v1&key=v2` 展开）
7. **CI 工作流**：`.github/workflows/ci.yml` 一站式跑 typecheck + test + build

## Why

左侧菜单是 admin 控制台的核心入口。之前 `Agents` 项仅 re-export Discovery（业务上是不同概念：Discovery 是只读浏览、Agents 应是注册管理），且没有任何面向 menu-level 的回归测试，导致：

- 任何 `pages/*.tsx` 改动都可能引入静默坏页面
- 补功能时缺一致的 mock 与 seed，重复工作
- CI 上不验证构建前端构建产物是否符合 contract

## Scope

| In | Out |
|---|---|
| `src/pages/Agents.tsx` 重写 | 后端 Agent 注册 API 改造 |
| `tests/fixtures/seed.ts` 新增 | 引入 MSW / Playwright（保持零新依赖） |
| `tests/fixtures/mockServer.ts` 新增 | 国际化文案调整 |
| `tests/harness.tsx` 重构 | AntD 主题改动 |
| `tests/Agents.test.tsx`、`tests/pages.coverage.test.tsx`、`tests/menu.e2e.test.tsx`、`tests/request.test.ts` 新增/扩展 |  |
| `tests/setup.ts` 增加 jsdom polyfills |  |
| `src/lib/request.ts` 增加 `params` 序列化 |  |
| `src/lib/api/agents.ts` 扩展 admin CRUD |  |
| `.github/workflows/ci.yml`、`package.json` scripts |  |

## 验收标准

- `npm run verify`（typecheck + test + build）全绿
- `npm test` 报 ≥ 49 passed，0 failed
- 0 个 TS error、0 个 antd console warning（除已知的 size chunk advisory）
- 各页面在 seed 数据下能成功渲染关键内容
- Agents 页 CRUD 流程：
  - 列表、关键字筛选、来源筛选
  - Drawer 新建（带名称正则校验、URL 校验、心跳超时校验）
  - 启停开关、删除 PopConfirm、连通性测试

## 影响

- 没有公开 API 改动（仅前端 + 测试）
- `src/pages/Agents.tsx` 接口签名不变，仍以默认 export 提供给路由
- 测试新增 `tests/fixtures/*`、`tests/harness.tsx`、`tests/Agents.test.tsx`、`tests/pages.coverage.test.tsx`

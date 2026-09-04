# tasks

## 设计阶段（已完成）

- [x] 评估 13 个菜单项完成度 → 发现 Agents 仅 re-export
- [x] 决定 Agents 与 Discovery 业务边界

## 实现（已完成）

- [x] `src/lib/api/agents.ts` 扩展：admin CRUD（listRegisteredAgents/get/register/update/delete/toggle/test/checkName）
- [x] `src/lib/request.ts` 增加 `params` 序列化
- [x] `src/pages/Agents.tsx` 重写：表格 + 筛选 + Drawer + 启停 + 测试 + 删除确认
- [x] `tests/fixtures/seed.ts` 种子数据：models / apiKeys / webhooks / audit / configVersions / agents / health / chatSessions
- [x] `tests/fixtures/mockServer.ts` 轻量 fetch mock（路由模板 + override + nextReply + 状态读写）
- [x] `tests/harness.tsx` 测试公共渲染器
- [x] `tests/setup.ts` jsdom polyfills
- [x] `tests/Agents.test.tsx` 11 个 CRUD 用例
- [x] `tests/pages.coverage.test.tsx` 13 个菜单页面在 seed 下的渲染验证
- [x] `tests/request.test.ts` 增加 params 序列化用例
- [x] `tests/README.md` 测试说明
- [x] `.github/workflows/ci.yml` CI：typecheck + test + build
- [x] `package.json` scripts：typecheck / test:coverage / verify

## 验收（已完成）

- [x] `npm run typecheck` 0 error
- [x] `npm test` 49 passed / 0 failed
- [x] `npm run build` 0 error
- [x] `npm run verify` 一次跑通

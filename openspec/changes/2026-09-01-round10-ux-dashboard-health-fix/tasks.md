# Tasks: Dashboard 健康判定修复

- [x] 1. 创建 openspec/changes/2026-09-01-round10-ux-dashboard-health-fix/{proposal,design,spec,tasks}.md
- [x] 2. 修复 Dashboard.tsx 健康汇总循环（识别 warning 分支 + 写 hasWarn）
- [x] 3. 实现 deriveHealthFromDetails 派生函数（latencyMs / cacheRate 阈值分段）
- [x] 4. 接入 cacheRate（已有 PromptCacheRate state）做 cache 派生
- [x] 5. 写 tests/Dashboard.test.tsx：覆盖 4 种状态组合
- [x] 6. 跑 npx vitest run 确认全绿
- [x] 7. 跑 npm run typecheck 确认无类型错误
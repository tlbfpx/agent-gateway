# Tasks: Feedback 标注端到端（round11-feedback-annotation）

## T1. OpenSpec 4 件套
- [x] proposal.md / design.md / spec.md / tasks.md

## T2. domain 层（已完成 R11-iter-1）
- [x] `FeedbackSentiment` enum + parse 别名
- [x] `FeedbackRecord` record + 不可变约束 + toMap
- [x] `FeedbackRepository` Port + FeedbackQuery + Summary
- [x] `FeedbackRecordTest` (6) + `FeedbackSentimentTest` (4) — **10/10 绿**

## T3. application 层（R11-iter-2）
- [ ] `FeedbackService` — recordFeedback / queryFeedback / summarize
- [ ] `FeedbackServiceTest` (5+ 用例)

## T4. interfaces 层（R11-iter-3）
- [ ] `FeedbackController` — POST / GET / /summary
- [ ] `FeedbackControllerTest` (@WebMvcTest)

## T5. persistence 层（R11-iter-4）
- [ ] `InMemoryFeedbackRepository` — CopyOnWriteArrayList + 内存过滤
- [ ] `InMemoryFeedbackRepositoryTest`
- [ ] bootstrap `FeedbackAutoConfiguration` 注入 InMemory

## T6. 前端组件（R11-iter-5）
- [ ] `lib/api/feedback.ts` — postFeedback / listFeedback / getSummary
- [ ] `components/feedback/FeedbackButtons.tsx` — 👍/👎 + 备注 modal
- [ ] `pages/Feedback.tsx` — 管理页(列表 + 筛选 + 统计卡 + 导出)
- [ ] `pages/Chat.tsx` — assistant 消息下挂按钮
- [ ] `pages/Traces.tsx` — Trace 详情侧拉挂按钮
- [ ] `routes.tsx` — 加 `/feedback` 路由
- [ ] `Sidebar.tsx` — 加"反馈"菜单项

## T7. 前端测试（R11-iter-6）
- [ ] `tests/FeedbackButtons.test.tsx`
- [ ] `tests/Feedback.test.tsx`

## T8. 门禁（R11-iter-7）
- [ ] `mvn -pl :gateway-domain,:gateway-application,:gateway-interfaces,:gateway-bootstrap -am test` 全绿
- [ ] `npx tsc --noEmit` 零错误
- [ ] `npx vitest run` 全绿
- [ ] `./verify.sh` 全绿
- [ ] 写 `docs/optimization-round11-report.md`

## 进度

- 2026-09-01 R11-iter-1: domain 层完成 + 10/10 测试绿

# Proposal: Feedback 标注端到端（round11-feedback-annotation）

> **状态**：Round 11 协作 + 数据闭环 #1
> **来源**：竞品分析报告 §六 B3 + Round 10 优化报告 §九 #1
> **借鉴**：Portkey Feedback、Langfuse Annotation Queues

## 动机

agent-gateway 已具备完整 trace/audit 链路,但**缺少用户对模型回复质量的反馈回路**：
- 用户无法告诉 PM「这条回答很有用 / 这条回答很糟糕」
- PM 无法从真实用户反馈里提炼 prompt 改进点
- 数据集评测缺真实标注来源,只能靠规则/LLM-as-judge

Portkey / Langfuse / Helicone 都把 👍/👎 + 备注 当作标配。本轮把它加进来。

## What

### 后端（domain + application + interfaces）

**domain 层** (`gateway-domain/feedback/`)
- `FeedbackRecord` record —— 一条标注(id, traceId, tenantId, userId, model, sentiment, score, comment, tags, metadata, createdAt)
- `FeedbackSentiment` enum —— POSITIVE / NEGATIVE / NEUTRAL
- `FeedbackRepository` Port —— save / findById / query(按 traceId/userId/model/sentiment/时间范围)
- `FeedbackQuery` record —— 查询参数封装

**application 层** (`gateway-application/feedback/`)
- `FeedbackService` 用例 —— recordFeedback / queryFeedback / summarize(给运营面板提供统计)
- `FeedbackSummary` record —— total / positive / negative / ratio / byModel / byDay

**interfaces 层** (`gateway-interfaces/feedback/`)
- `FeedbackController` ——
  - `POST /v1/feedback` 记录反馈(用户/SDK 调用)
  - `GET /v1/feedback` 查询(管理端)
  - `GET /v1/feedback/summary` 汇总统计

**persistence 层** (`gateway-infra-persistence/feedback/`)
- `InMemoryFeedbackRepository` —— P0 默认实现(内存 + List 过滤)
- `PgFeedbackRepository` —— Round 12 接入(jdbcTemplate + postgres 表)

### 前端

- `pages/Feedback.tsx` (新) —— 反馈管理页(列表 + 筛选 + 统计卡 + 导出)
- `components/feedback/FeedbackButtons.tsx` (新) —— 👍/👎 双按钮 + 备注 modal
- `pages/Chat.tsx` —— 每条 assistant 消息下挂 FeedbackButtons
- `pages/Traces.tsx` —— Trace 详情侧拉里挂 FeedbackButtons(快速给某次调用打标)
- `lib/api/feedback.ts` (新) —— postFeedback / listFeedback / getFeedbackSummary
- 路由:`/feedback`

### OpenSpec 流程

本 change 拆为两阶段:
- **Round 11 主**:domain + application + interfaces + InMemory persistence + 前端按钮 + 管理页 + 测试
- **Round 12 增量**:Pg 持久化 + PII 脱敏 + TTL 自动归档 + 导出 CSV/JSONL

## Non-goals

- 不做 LLM-as-judge(留 Round 14 数据集)
- 不做团队协作(留 Round 12 多 Admin)
- 不做 SSO 鉴权(沿用 X-API-Key Admin Token)
- 不改 audit 链路(feedback 单独表,只在事件层联动)

## 验收

- `POST /v1/feedback` 200 OK,记录可查
- `GET /v1/feedback?traceId=xxx` 返回该 trace 的所有标注
- `GET /v1/feedback/summary` 返回聚合统计
- Chat 页面 assistant 消息下出现 👍/👎 按钮,点击调通接口
- Traces 详情侧拉有"为这次调用加反馈"入口
- `/feedback` 管理页有列表 + 统计 + 筛选
- 测试:domain 单元测试 + application 单元测试 + 前端 vitest 全绿

## 风险与权衡

| 风险 | 缓解 |
|---|---|
| 用户内容被存(合规) | 备注字段 maxLength 500;评论做 PII 标记提示;TTL 90 天(留 Round 12) |
| 反馈被滥用(刷量) | per-user-per-trace 唯一约束(数据库层;InMemory 阶段先不限制) |
| 接口未鉴权 | POST 沿用 X-API-Key;GET admin 端加 X-Admin-Token(与 Audit 对齐) |

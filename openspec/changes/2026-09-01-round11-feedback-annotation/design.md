# Design: Feedback 标注端到端（round11-feedback-annotation）

> 与 proposal.md 配套：列模块边界 / 数据流 / 状态机 / 与既有模块关系

## 模块边界

```
gateway-domain/feedback/
  ├── FeedbackSentiment.java        # enum + 字符串解析(API 友好别名)
  ├── FeedbackRecord.java           # record + 不可变约束
  └── FeedbackRepository.java       # Port + FeedbackQuery + Summary

gateway-application/feedback/
  └── FeedbackService.java          # 用例:record/query/summarize

gateway-interfaces/feedback/
  └── FeedbackController.java       # REST:POST /v1/feedback + GET + /summary

gateway-infra-persistence/feedback/
  ├── InMemoryFeedbackRepository.java  # P0:CopyOnWriteArrayList + 内存过滤
  └── PgFeedbackRepository.java        # P1(R12):JdbcTemplate + pg 表
```

## 数据流

```
┌────────┐  POST /v1/feedback         ┌──────────────┐
│ Client │ ─────────────────────────► │ Controller   │
│ (Chat/ │                            │ (interfaces) │
│  Trace │  GET /v1/feedback          └──────┬───────┘
│  /SDK) │ ◄─────────────────────────       │
└────────┘                                  ▼
                                  ┌──────────────────┐
                                  │ FeedbackService  │
                                  │  (application)   │
                                  └────────┬─────────┘
                                           ▼
                                  ┌──────────────────┐
                                  │ FeedbackRepo Port│
                                  └────────┬─────────┘
                                           ▼
                          ┌────────────────────────────────┐
                          │ InMemoryFeedbackRepository(P0) │
                          │ PgFeedbackRepository    (P1 R12)│
                          └────────────────────────────────┘
```

## 状态机

Feedback 是 append-only，无显式状态机。隐含规则：
- `id == 0` 表示未持久化（in-memory）
- 持久化后 `id > 0`，不可改（用户撤回 = 物理删除，不留 tombstone）
- sentiment 与 score 互补：sentiment 必填，score 可空
- per-user-per-trace 一条限制（R12 数据库层做唯一约束；P0 InMemory 不做）

## 与既有模块关系

| 既有模块 | 关系 | 说明 |
|---|---|---|
| **observability/SpanRecord** | 通过 traceId 关联 | 不直接 join;UI 跳转 `/traces?traceId=xxx` |
| **audit/AuditRepository** | 事件层联动 | FeedbackService.record 发出 `feedback.recorded` 审计事件,审计模块订阅 |
| **security/SensitivePiiFilter** | 可选挂钩 | comment 字段超 200 字符时过一遍 PII 检测(R12) |
| **replay/TraceReplayService** | 间接 | 数据集评测用 feedback 作为真实标注来源(R14) |
| **chat/ChatController** | 同进程 | 不直接调用;前端拿到 traceId 后单独调 /v1/feedback |
| **bootstrap** | 自动配置 | `FeedbackAutoConfiguration`(R12),注入 InMemory 实现 + 路由 |

## 测试覆盖

| 层 | 用例 |
|---|---|
| domain | FeedbackRecordTest (6) + FeedbackSentimentTest (4) — 本次提交 |
| domain | FeedbackRepository 契约测试(用 InMemory 实现) — R12 |
| application | FeedbackServiceTest — R12 |
| interfaces | FeedbackControllerTest(@WebMvcTest) — R12 |
| infra-persistence | InMemoryFeedbackRepositoryTest — R12 |
| infra-persistence | PgFeedbackRepositoryTest — R12 |
| 前端 | FeedbackButtons.test.tsx + Feedback.test.tsx — R12 |

## 部署与回滚

- P0 阶段 InMemory，重启即丢；标注数据视作短期运营反馈(不影响生产决策)
- R12 切 Pg 后 PII 脱敏 + TTL 90d 自动归档
- 回滚：删除 `gateway-bootstrap` 的 `FeedbackAutoConfiguration` import 即可

## 安全与合规

- POST 走 X-API-Key(沿用 chat 同链路)
- GET /v1/feedback + GET /v1/feedback/summary 走 X-Admin-Token(与 AdminAudit 对齐)
- comment 字段 ≤ 500 字符(record 层强制截断)
- 不存 raw 用户输入(text) — 只存 traceId/userId 引用,R12 加 PII 检测

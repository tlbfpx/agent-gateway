# Spec: Feedback 标注端到端（round11-feedback-annotation）

> 行为契约：5 个 SHALL + 4 个 WHEN/THEN 场景

## 行为要求（SHALL）

1. The system **SHALL** accept user feedback via `POST /v1/feedback` with required fields `traceId`, `tenantId`, `sentiment`; optional `spanId`, `userId`, `model`, `score (1-5)`, `comment (≤500)`, `tags[]`, `metadata{}`.
2. The system **SHALL** parse sentiment from any of: `POSITIVE/positive/thumbs_up/👍/good` → POSITIVE; `NEGATIVE/negative/thumbs_down/👎/bad` → NEGATIVE; `NEUTRAL/neutral/ok/fine` → NEUTRAL.
3. The system **SHALL** persist feedback via `FeedbackRepository.save` returning the new id; subsequent `findById`/`findByTraceId`/`query` calls **SHALL** see the record.
4. The system **SHALL** reject feedback with score outside 1..5, blank traceId, blank tenantId, or null sentiment; each rejection **SHALL** yield HTTP 400 with a structured `{error, field, message}` body.
5. The system **SHALL** truncate comments over 500 characters silently to 500 characters (record-level invariant).

## 场景（WHEN/THEN）

### Scenario 1: User submits 👍 from Chat
**WHEN** client `POST /v1/feedback` with `{"traceId":"tr-1","tenantId":"au","sentiment":"thumbs_up","comment":"great","score":5}`
**THEN** server returns `201 Created` with body `{"id":42,"createdAt":"2026-09-01T..."}` and the record is retrievable via `GET /v1/feedback?traceId=tr-1`.

### Scenario 2: SDK submits with alias
**WHEN** client `POST /v1/feedback` with `{"traceId":"tr-2","tenantId":"au","sentiment":"👎","tags":["hallucination"]}`
**THEN** server persists sentiment=NEGATIVE with tags=["hallucination"]; `GET /v1/feedback?traceId=tr-2` returns it.

### Scenario 3: Reject invalid score
**WHEN** client `POST /v1/feedback` with `score=7`
**THEN** server returns `400 Bad Request` with `{"error":"invalid_score","field":"score","message":"score must be 1..5, got 7"}`.

### Scenario 4: Reject unknown sentiment
**WHEN** client `POST /v1/feedback` with `sentiment="love-hate"`
**THEN** server returns `400 Bad Request` with `{"error":"invalid_sentiment","message":"unknown sentiment: love-hate (use positive|negative|neutral)"}`.

### Scenario 5: Management query
**WHEN** admin `GET /v1/feedback?model=gpt-4o&sentiment=NEGATIVE&limit=10`
**THEN** server returns up to 10 records matching filter, sorted by createdAt desc.

### Scenario 6: Summary aggregation
**WHEN** admin `GET /v1/feedback/summary?from=2026-08-01&to=2026-09-01`
**THEN** server returns `{"total":120,"positive":80,"negative":30,"neutral":10,"positiveRatio":0.667,"byModel":[{...}],"topTags":[{...}]}`.

### Scenario 7: Chat UI shows 👍/👎 buttons
**WHEN** user views a chat message from assistant
**THEN** two thumbs buttons appear below message; clicking one opens a modal for optional comment; submitting closes modal and shows confirmation.

### Scenario 8: Trace detail shows feedback count
**WHEN** user opens a Trace in `/traces`
**THEN** the right-drawer displays the count of 👍 and 👎 submissions for that trace.

## 验收对应（DoD）

- [x] domain 层编译通过 + 单测 10/10 绿
- [ ] application 层 + 单测
- [ ] interfaces 层 + 单测
- [ ] InMemory 持久化 + 单测
- [ ] 前端 FeedbackButtons 组件 + 单测
- [ ] 前端 /feedback 管理页 + 单测
- [ ] Chat 接入 + 单测
- [ ] Traces 接入 + 单测
- [ ] verify.sh 全绿
- [ ] vitest 全绿

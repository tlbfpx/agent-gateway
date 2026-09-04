# Proposal: Trace 重放增强（round8-replay-enhancement）

> **状态**：实现已完成（commit `81ef410f`），本变更记录为事后补档
> **来源**：Round 7 引入 Trace UI（waterfall / 一键 replay 原型），Round8 把 replay 升级为生产可用

## 动机

Round 7 的 Trace UI 提供"一键 replay"原型按钮，但实际生产场景的 replay 需要：
- **安全**：mutating 操作（写数据库、调外部 API、扣款）在 replay 时不应重复执行
- **隔离**：metrics token 不能跨 trace 共享，否则同一指标被两次计入
- **可观测**：callback URL 完成后异步 POST，签名 + 重试 + DLQ
- **审计**：replay 产生新 traceId，但能与原 trace 关联展示（diff 视图）
- **限流**：批量 replay / 压测不能打爆下游

Round 7 之前这些都没考虑；Round 8 把 replay 升级为生产级特性。

## What

### 领域接口（gateway-domain/replay）
- `PayloadCapturePort`：trace payload 捕获与加密（REQUEST / RESPONSE / TOOL_CALL / TOOL_RESULT 四种角色）
- `PayloadCaptureHelper`：门面服务（captureRequest / captureResponse / captureToolCall / captureToolResult）
- `PayloadRecord`：单条 payload 记录（traceId + spanId + role + contentType + body + bytes + capturedAt）
- `ReplayRequest` / `ReplayResult`：replay 请求/结果 DTO（含 traceId / overrides / safeReplay / async / callbackUrl）
- `TraceDiffService`：trace 差异比对（高亮新增/修改/删除的 span）
- `MetricsQueryPort`：历史 metrics 查询端口（供 replay 链路分析）

### 应用层（gateway-application/replay）
- `ReplayService`：门面编排（参数校验 + 异步执行 + 回调签名）
- `ReplayAsyncExecutor`：有界队列 + 背压的异步执行器
- `CallbackSigner`：HMAC 签名（对接 gateway-replay-sdk 的 `CallbackVerifier`）

### 基础设施（gateway-infra-persistence/replay）
- `PgPayloadStore`：PG 持久化 payload（AES-256-GCM 加密）
- `PgMetricsTokenStore`：metrics token 隔离（每次 replay 发新 token）
- `CachedMetricsTokenStore`：Caffeine 装饰器（本地 LRU 缓存）
- `PayloadCipher`：AES-256-GCM 加解密（密钥从装配层注入）
- `ReplaySchemaInitializer` + `schema-replay.sql`：PG schema bootstrap

### Web API（gateway-interfaces/replay）
- `AdminReplayController`：
  - `POST /v1/admin/traces/{traceId}/replay`：单条同步重放
  - `POST /v1/admin/traces/{traceId}/replay/batch`：批量变体重放
  - `POST /v1/admin/traces/replay/load`：压测（并发 + 持续时长）
  - `GET  /v1/admin/traces/{traceId}/diff?against=…`：与另一条 trace 对比
  - `GET  /v1/admin/replay/jobs?jobId=…`：查 job 状态
  - `GET  /v1/admin/replay/jobs/recent?limit=20`：最近 N 条 jobs

### UI
- `lib/api/replay.ts`：前端 SDK（replayTrace / replayDiff / replayBatch / replayLoad）
- `e2e/replay.spec.ts`：Playwright 端到端覆盖触发 → 异步执行 → diff 展示全流程
- `pages/Traces.tsx`：增加 replay 弹窗 + 集成 diff 视图

## Non-goals

- 不做长留存（payload 默认 30 天，超期由定时任务清理）
- 不做 replay 链路追踪（OTel 自动捕获，replay SDK 不重复埋点）
- 不做跨语言 replay SDK（Java/C#/Go 各语言消费方用各自语言对接 CallbackVerifier 协议）

## 验收

- 后端：domain/replay + application/replay + persistence/replay + interfaces/replay 测试全过
- 集成：mutating 操作在 safeReplay=true 时正确跳过 LLM 上下文（SafeReplayMutatingTest 验证）
- UI：replay 弹窗 + diff 视图渲染，e2e Playwright 全流程通过
- 配置：`gateway.replay.enabled=true`（默认 false，向后兼容）
- 测试覆盖：PayloadCipher 加解密 / 异步重试 / callback HMAC 签名 / mutating 跳过 / trace diff
# Spec: Trace 重放增强（可测试条款）

#### GW-REPLAY-001 Payload 加密存储
**MUST**：PgPayloadStore 用 AES-256-GCM 加密 trace_payloads.body_enc 列；解密失败抛 `IllegalStateException` 不返回空。
**测试**：PayloadCipherTest.encryptDecryptRoundtrip / PgPayloadStoreTest.encryptsBeforeInsert。

#### GW-REPLAY-002 PII 阻断
**MUST**：PayloadCaptureHelper 收到 PII query 时跳过 capture（已有 PiiDetector 在 domain/cache；此处复用规则）。
**测试**：PayloadCaptureHelperTest.piiQuery_skipsCapture。

#### GW-REPLAY-003 Async 重放不阻塞响应
**MUST**：`ReplayService.replay()` 在 callbackUrl 非空时立刻返回 PENDING，异步执行；队列满返回 429 + 拒绝新任务。
**测试**：ReplayServiceTest.asyncReturnsPendingImmediately / ReplayAsyncExecutorTest.queueFullReturns429。

#### GW-REPLAY-004 Safe replay 跳过 mutating
**MUST**：`safeReplay=true` 时跳过 ToolDescriptor.mutating=true 的工具，**不调 LLM 决策**；跳过次数记录到 ReplayResult。
**测试**：SafeReplayMutatingTest.mutatingToolSkipped / MutatingSkipLlmContextTest.llmNotCalledForMutating。

#### GW-REPLAY-005 Trace diff 算法
**MUST**：`TraceDiffService.diff(traceA, traceB)`）返回 DiffResult（新增/修改/删除的 span 列表 + similarity score 0~1）。
**测试**：TraceDiffServiceTest.identicalTracesSimilarityOne / differentTracesShowDiff / emptyDiffReturnsEmpty。

#### GW-REPLAY-006 Callback HMAC 签名
**MUST**：callback POST 带 `X-Replay-Timestamp` + `X-Replay-Signature` 头；签名 = HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)；5 分钟时间戳窗口。
**测试**：CallbackSignerTest.signAndVerifyRoundtrip / expiredTimestampRejected。

#### GW-REPLAY-007 Metrics token 隔离
**MUST**：每次 replay 生成新 metrics token（UUID）；PgMetricsTokenStore 按 token 维度存储；CachedMetricsTokenStore Caffeine LRU 缓存。
**测试**：PgMetricsTokenStoreTest.tokenIsolatesMetrics / CachedMetricsTokenStoreTest.lruEviction。

#### GW-REPLAY-008 Replay 端点完备
**MUST**：`/v1/admin/traces/{traceId}/replay`、`/replay/batch`、`/replay/load`、`/diff`、`/replay/jobs?jobId`、`/replay/jobs/recent` 全部端点存在且返回正确 status code。
**测试**：AdminReplayControllerTest.allEndpointsReturnOk。

#### GW-REPLAY-009 Batch 变体重放
**MUST**：`POST /replay/batch` 接收变体列表（多条 overrides），并发执行（默认 5 并发），返回 job 列表（含每条结果）。
**测试**：ReplayServiceTest.batchRunsConcurrently。

#### GW-REPLAY-010 压测（load）端点
**MUST**：`POST /replay/load` 接收并发数 + 持续时长 + QPS；按配置启动压测，5 秒后上报当前 RPS / 错误率 / P99 延迟。
**测试**：ReplayServiceTest.loadReportsMetricsAfterInterval。

#### GW-REPLAY-011 配置开关
**MUST**：`gateway.replay.enabled=false`（默认）时，PayloadCaptureHelper / ReplayService 都为 no-op，运营端点返回 503。
**测试**：ReplayServiceTest.disabledReturnsNoOp / AdminReplayControllerTest.disabledReturns503。

#### GW-REPLAY-012 Retention 清理
**MUST**：定时任务（每日凌晨）执行 purgeBefore(cutoff)，按 `retention-days` 配置清理过期 payload。
**测试**：PgPayloadStoreTest.purgeBeforeDeletesExpiredRows（IT）。

#### GW-REPLAY-013 Schema 幂等
**MUST**：`schema-replay.sql` 重复执行不报错（CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS）。
**测试**：ReplaySchemaInitializerTest.idempotentSchemaInit。
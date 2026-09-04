# Design: Trace 重放增强

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| Payload 存储 | PG `trace_payloads` 表，body AES-256-GCM 加密 | 防止明文落盘泄漏敏感 query；密钥从 secret store 派生 |
| Trace diff 算法 | SpanView 字段级 LCS（最长公共子序列） | 业界通用算法；O(n²) 适合中等 trace（<500 span） |
| 异步执行器 | 有界 LinkedBlockingQueue + 背压 | 防止重放风暴打爆下游；满则拒绝并返回 429 |
| Callback 签名 | HMAC-SHA256(timestamp + method + path + body) | 与下游消费方 gateway-replay-sdk 对齐，5 分钟时钟偏移容差 |
| Metrics token 隔离 | 每次 replay 发新 token，写到 PG `replay_metrics_tokens` 表 | 同一指标不被重复计入 |
| Safe replay 跳过 mutating | ToolDescriptor.mutating=true 的工具在 replay 时直接跳过，不调 LLM 决策 | 防止写数据库/调外部 API 重放 |
| Retention | 默认 30 天，运营可配 7/30/90/365 | 防止 PG 表无限增长 |
| 性能 | replay 链路不影响主链路（fire-and-forget 异步） | 用户无感 |

## 2. 数据流

```
ChatController.stream()
  ↓
ChatOrchestrator.run() 入口
  ├─ captureHelper.captureRequest("trace-orchestrator", prompt, model)
  ↓
LLM 调用
  ├─ captureHelper.captureResponse(traceId, responseText, tokensIn, tokensOut)
  ├─ tool_call 前 → captureToolCall
  └─ tool_call 后 → captureToolResult
  ↓ (异步 fire-and-forget)

运营台触发 replay：
POST /v1/admin/traces/{traceId}/replay
  ↓
AdminReplayController → ReplayService.replay(req, apiKey, tenantId)
  ├─ 从 PayloadCapturePort 还原原请求（prompt / model / tools）
  ├─ 应用 ReplayOverrides（覆盖字段优先；null 字段用原值）
  ├─ 调用 ChatOrchestrator.orchestrate() 走同一路径（产生新 traceId）
  ├─ Safe Mode：跳过 mutating=true 的 tool
  ├─ 同步模式：返回 ReplayResult（含 newTraceId + fullText）
  └─ Callback 模式：异步执行 → POST callbackUrl（HMAC 签名）
  ↓
ReplayResult 上报 metrics（新 token，隔离计入）
  ↓
UI 显示：trace 列表 + diff 视图（origin vs replayed）
```

## 3. 配置

```yaml
gateway:
  replay:
    enabled: ${REPLAY_ENABLED:false}
    payload-key-ref: ${REPLAY_PAYLOAD_KEY:}      # 32 字节密钥;空 = 使用 no-op cipher
    retention-days: ${REPLAY_RETENTION_DAYS:30}
    async-queue-size: 100                        # 背压阈值
    callback-secret: ${REPLAY_CALLBACK_SECRET:}  # HMAC 密钥
    safe-replay-default: true                     # 默认跳过 mutating
```

## 4. 风险与权衡

| 风险 | 缓解 |
|---|---|
| Payload 泄漏（明文 query 含敏感信息） | AES-256-GCM 加密 + PII 检测阻断写入 |
| Callback URL 被劫持 | HMAC 签名 + 5 分钟时间戳窗口 + 重试 + DLQ |
| 重放风暴（压测滥用） | 有界队列 + 429 + 速率限制 |
| Mutating 工具误执行 | safeReplay 默认 true；运营可显式关闭但记录 audit log |
| Diff 算法慢 | O(n²) 仅在 <500 span 时实用；>500 span 用抽样或聚合 |
| Metrics 重复计入 | 每次 replay 发新 token，PG 表按 token 维度聚合 |

## 5. 涉及文件

| 模块 | 文件 |
|---|---|
| gateway-domain/replay | PayloadCapturePort / PayloadCaptureHelper / PayloadRecord / ReplayRequest / ReplayResult / TraceDiffService / MetricsQueryPort |
| gateway-domain/test/replay | PayloadCaptureHelperTest / TraceDiffServiceTest |
| gateway-application/replay | ReplayService / ReplayAsyncExecutor / CallbackSigner + 测试 |
| gateway-application/test | SafeReplayMutatingTest / SafeReplayMutatingE2ETest / MutatingSkipLlmContextTest |
| gateway-infra-persistence/replay | PgPayloadStore / PgMetricsTokenStore / CachedMetricsTokenStore / PayloadCipher / ReplaySchemaInitializer / schema-replay.sql + 测试 |
| gateway-interfaces/replay | AdminReplayController |
| agent-gateway-ui | lib/api/replay.ts / e2e/replay.spec.ts / pages/Traces.tsx（replay 弹窗 + diff 视图） |
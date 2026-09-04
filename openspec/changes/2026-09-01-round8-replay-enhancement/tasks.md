# Tasks: Trace 重放增强（round8-replay-enhancement）

- [x] **A.1** 领域接口：PayloadCapturePort / PayloadCaptureHelper / PayloadRecord / ReplayRequest / ReplayResult / TraceDiffService / MetricsQueryPort
- [x] **A.2** 应用层：ReplayService / ReplayAsyncExecutor / CallbackSigner
- [x] **A.3** 基础设施：PgPayloadStore / PgMetricsTokenStore / CachedMetricsTokenStore / PayloadCipher / ReplaySchemaInitializer / schema-replay.sql
- [x] **A.4** Web API：AdminReplayController 6 端点（replay / batch / load / diff / jobs / recent）
- [x] **A.5** ChatOrchestrator 集成：入口 captureRequest / tool_call captureToolCall / tool_result captureToolResult
- [x] **A.6** UI：lib/api/replay.ts + pages/Traces.tsx（replay 弹窗 + diff 视图）
- [x] **A.7** E2E：e2e/replay.spec.ts（Playwright 端到端覆盖）
- [x] **B.1** 测试套件：domain/replay + application/replay + persistence/replay + interfaces/replay
- [x] **B.2** 测试套件：SafeReplayMutatingTest / SafeReplayMutatingE2ETest / MutatingSkipLlmContextTest（safe replay 核心场景）
- [x] **B.3** 集成验证：mvn -pl gateway-domain,gateway-application,gateway-interfaces -am test BUILD SUCCESS
- [x] **C.1** 文档：openspec 变更记录（proposal / design / spec / tasks）

## 验收门禁

- [x] 后端单测：domain/replay + application/replay + persistence/replay 测试套件全过
- [x] safe replay 核心：SafeReplayMutatingTest + MutatingSkipLlmContextTest 全过
- [x] 单模块 verify：`mvn -pl gateway-domain,gateway-application,gateway-interfaces -am test` BUILD SUCCESS
- [x] Spec 条款 GW-REPLAY-001 ~ GW-REPLAY-013 全部覆盖
- [x] Round 8 commit `81ef410f` 落地
- [x] Round 8.5 fix `a9769bd8`（Spring 4.0 严格模式）落地，verify.sh 11 模块全绿
# Tasks: Guardrails（round9-guardrails）

- [x] **A.1** 领域接口：GuardrailPolicy / GuardrailMode / GuardrailViolation / GuardrailPort / GuardrailFacade / PiiRedactor / DefaultGuardrailLibrary
- [x] **A.2** 单元测试：GuardrailFacadeTest 7 例（disabled / BLOCK / OBSERVE / REDACT / 白名单 / 黑名单 / 热更新）
- [x] **B.1** 基础设施：DefaultGuardrailService（实现 GuardrailPort）+ GuardrailAutoConfiguration
- [x] **B.2** 单元测试：DefaultGuardrailServiceTest 10 例（PII 邮箱/手机 / jailbreak 中英 / toxicity / 白名单 / 黑名单 / 干净 query / 热更新 / 内置规则库）
- [x] **C.1** 集成：ChatOrchestrator.setGuardrailFacade + run() 入口 checkInput（BLOCK 模式早退）
- [x] **C.2** 集成：GuardrailViolation 上报 audit log
- [x] **D.1** Web API：GuardrailAdminController（GET/POST /v1/admin/guardrails/policy + /stats）
- [x] **E.1** UI：pages/Guardrails.tsx + lib/api/guardrails.ts + Sidebar / Routes 注册
- [x] **F.1** 文档：openspec 变更记录（proposal / spec / tasks）

## 验收门禁

- [x] 域零框架（GW-GRD-014）：domain/safety 无 SLF4J / Spring 依赖
- [x] 单模块 verify：`mvn -pl gateway-domain,gateway-infra-security,gateway-application test` BUILD SUCCESS
- [x] Spec 条款 GW-GRD-001 ~ GW-GRD-014 全部覆盖（含测试引用）
- [x] 内置规则库：PII ≥4 / Jailbreak ≥20 / Toxicity ≥30（中英文基线）

## Round 9 后续（P2/P3）

- GW-GRD-009 完整化：checkOutput 输出脱敏 + checkToolCall 集成到 ChatOrchestrator 工具调用前
- GW-GRD-010 完整化：违规 events publish（`guardrail.violation`）到 GatewayEvents + metrics
- GW-GRD-013 性能预算：JMH benchmark 验证 P99 ≤ 5ms
- GW-GRD-011 增强：策略历史 + diff 视图
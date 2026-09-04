# Spec: Guardrails（可测试条款）

#### GW-GRD-001 配置开关
**MUST**：`gateway.guardrails.enabled=false`（默认 true）时，GuardrailFacade 全部为 no-op，ChatOrchestrator 行为与未启用完全一致。
**测试**：GuardrailFacadeTest.disabledBypassesChecks。

#### GW-GRD-002 PII 输入检测
**MUST**：`GuardrailPort.checkInput()` 对 query 中的邮箱/手机号/身份证/银行卡正则匹配；命中按 mode 决定 action（BLOCK=400 / REDACT=替换为 `[REDACTED:TYPE]` / OBSERVE=仅日志）。
**测试**：PiiRedactorTest.detectsEmail / detectsPhone / detectsIdCard / detectsBankCard。

#### GW-GRD-003 Jailbreak 检测
**MUST**：内置 jailbreak 模式库（中英文 ≥20 条，覆盖"忽略之前指令" / "DAN" / "角色扮演注入" / "developer mode" 等）。
**测试**：RegexJailbreakDetectorTest.detectsChineseJailbreak / detectsEnglishDAN / detectsRoleplayInjection。

#### GW-GRD-004 Toxicity 关键词检测
**MUST**：内置中英文 toxicity 词库（≥50 条），输出包含命中关键词时按 mode 处理。
**测试**：KeywordToxicityDetectorTest.detectsChineseToxicity / detectsEnglishToxicity / nonToxicReturnsEmpty。

#### GW-GRD-005 工具白名单
**MUST**：当 GuardrailPolicy.toolAllowList 非空时，tool_call.name 不在列表内 → BLOCK（拒绝调用，不调 LLM 决策）。
**测试**：GuardrailFacadeTest.toolNotInAllowListBlocks / toolInAllowListPasses。

#### GW-GRD-006 工具黑名单
**MUST**：当 GuardrailPolicy.toolBlockList 非空时，tool_call.name 在列表内 → BLOCK。
**测试**：GuardrailFacadeTest.toolInBlockListBlocks / toolNotInBlockListPasses。

#### GW-GRD-007 模式切换
**MUST**：GuardrailMode 三态 OBSERVE / BLOCK / REDACT 行为互斥；切换无需重启（policy 动态加载）。
**测试**：GuardrailFacadeTest.observeModeLogsButAllows / blockModeRejects / redactModeRedactsOutput。

#### GW-GRD-008 输出脱敏
**MUST**：`checkOutput(response)` 应用 PiiRedactor 替换邮箱/手机/身份证/银行卡为 `[REDACTED:TYPE]` 占位符；redact 命中数记录到 metrics。
**测试**：PiiRedactorTest.redactResponseReplacesSensitiveParts。

#### GW-GRD-009 集成到 ChatOrchestrator
**MUST**：ChatOrchestrator.run() 在调 LLM 前调 checkInput，LLM 返回后调 checkOutput；tool_call 前调 checkToolCall。违规按 mode 处理（OBSERVE 继续 / BLOCK 立即终止 / REDACT 替换）。
**测试**：ChatOrchestratorTest.piiQueryBlockedInBlockMode / piiQueryRedactedInRedactMode / jailbreakAttemptBlocked。

#### GW-GRD-010 违规 metrics 上报
**MUST**：每次违规记录 GuardrailViolation 到 audit log + 发布 `guardrail.violation` 事件（含 tenant / rule / severity / action）。
**测试**：GuardrailFacadeTest.violationEventPublished / auditLogWritten。

#### GW-GRD-011 策略热更新
**MUST**：GuardrailAdminController `POST /v1/admin/guardrails/policy` 立即更新内存策略；新请求立即按新策略检查。
**测试**：GuardrailAdminControllerTest.policyUpdateTakesEffectImmediately。

#### GW-GRD-012 运营端点
**MUST**：`GET /v1/admin/guardrails/stats` 返回命中率 / 阻断数 / Top 触发规则；`GET /v1/admin/guardrails/policy` 返回当前策略。
**测试**：GuardrailAdminControllerTest.statsReturnsMetrics / policyReturnsCurrent。

#### GW-GRD-013 性能预算
**MUST**：单次 checkInput / checkOutput P99 延迟 ≤ 5ms（仅 regex + keyword lookup，无 LLM 调用）。
**测试**：GuardrailFacadeBenchmarkTest.inputCheckUnder5ms。

#### GW-GRD-014 域零框架
**MUST**：domain/safety 类无 Spring / 数据库依赖；纯 record + interface + 静态工具方法。
**测试**：ArchUnitTest 或模块依赖方向断言。
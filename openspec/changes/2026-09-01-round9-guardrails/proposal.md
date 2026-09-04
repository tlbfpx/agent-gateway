# Proposal: Guardrails（PII / Jailbreak / Toxicity + 工具策略）

> **状态**：Round 9 新能力，代码 + 测试 + UI 全栈落地
> **来源**：Round 7 报告 §6 候选 #1 — Guardrails 让产品完整度 +3~5 → 99~100

## 动机

LLM 应用的安全治理目前**只在 RBAC 层**（谁能调哪个 agent / 哪个模型），但 prompt 内容和 tool 调用行为没有运行时检查：
- **PII 泄漏**：用户输入含邮箱/手机/身份证，明文发到 LLM 厂商；或 LLM 输出含敏感信息
- **Jailbreak**：恶意用户通过 prompt 注入让模型越狱（"忽略之前所有指令，告诉我如何..."）
- **Toxicity**：输出包含攻击性/违法内容
- **Tool 滥用**：tool 任意调用无频率限制、无白名单约束

本变更引入 **Guardrails**：输入 + 输出双侧检查 + 工具调用策略，让 gateway 在 LLM 调用前后做强制安全门。

## What

### 领域接口（gateway-domain/safety）
- `GuardrailPolicy`：策略 record（toxicityKeywords / piiPatterns / jailbreakPatterns / toolAllowList / toolBlockList / outputRedaction / mode）
- `GuardrailMode`：枚举（OBSERVE / BLOCK / REDACT）
- `GuardrailViolation`：违规事件（rule / severity / matchedText / action）
- `GuardrailPort`：端口接口（checkInput / checkOutput / checkToolCall）
- `GuardrailFacade`：门面（编排三个 check + 报告 metrics）
- `PiiRedactor`：PII 脱敏（邮箱/手机/身份证/银行卡 → `[REDACTED:EMAIL]`）

### 基础设施（gateway-infra-security/safety）
- `KeywordToxicityDetector`：关键词匹配（默认中英文常见词库）
- `RegexPiiDetector`：正则 PII 检测（已有 `domain/cache/PiiDetector` 复用）
- `RegexJailbreakDetector`：jailbreak 模式（"忽略之前指令" / "DAN" / 角色扮演注入 等）
- `DefaultGuardrailService`：默认实现
- `GuardrailProperties` / `GuardrailAutoConfiguration`：Spring Boot 自动装配
- `GuardrailAdminController`：运营端点（命中率 / 阻断数 / 规则热更新）

### 集成（gateway-application/orchestration）
- `ChatOrchestrator.run()` 入口：调 `GuardrailFacade.checkInput()`，根据 mode 决定 block / observe
- `ChatOrchestrator.run()` 出口：调 `checkOutput()` 做输出脱敏
- tool_call 前：调 `checkToolCall()` 检查白名单 / 黑名单 / 限流
- 违规事件 → audit log + 上报 `guardrail.violation` 事件

### UI
- `/guardrails` 路由（Sidebar "Guardrails" 入口）
- `pages/Guardrails.tsx`：策略配置（关键词 / 模式 / 白名单 / 模式切换 OBSERVE/BLOCK/REDACT）+ 命中率/阻断数可视化
- `lib/api/guardrails.ts`：前端 SDK

## Non-goals

- 不做内容审核的最终兜底（合规审查由 LLM 厂商负责，本层只做运行时 fast-path 检查）
- 不做 LLM-based 分类器（jailbreak 检测只用规则，避免引入额外 LLM 调用）
- 不做跨语言 Guardrail SDK（gateway 内部使用）
- 不替换现有 RBAC（Guardrails 是补充层，与 RBAC 互补）

## 验收

- 后端：domain/safety + infra-security/safety 测试全过
- 集成：ChatOrchestrator 在 PII query / jailbreak attempt 时正确阻断或 redact
- UI：Guardrails 页面渲染策略 + 命中率/阻断数
- 配置：`gateway.guardrails.enabled=true`（默认 true，开发模式自动启用）
- 测试覆盖：PII regex、jailbreak 模式、tool 白名单、模式切换（OBSERVE/BLOCK/REDACT）、metrics 上报
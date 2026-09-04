# Proposal: 成本中心与审计（add-cost-and-audit）

> **状态：✅ 已完成**（2026-08-14）。

## 变更概述

实现 domain 成本/审计端口 + infra 持久化（InMemory 默认 + 预留 Redis/DB）。spec §21（成本）+ §22（审计）。成本核算（token×单价 → cost）+ 审计日志（append-only 合规追溯）。

## What / 范围

### 做
- **domain 成本**：`UsageRecord` record + `CostRepository` 端口（记录用量、聚合查询、预算）。`Budget` record + 预算校验。
- **domain 审计**：`AuditLog` record + `AuditEvent` 类型 + `AuditRepository` 端口（append-only 记录 + 查询）。
- **infra**：InMemory 实现（默认）+ 条件装配。
  - `InMemoryCostRepository`：用量记录、按租户/模型/日期聚合、预算管理。
  - `InMemoryAuditRepository`：审计日志 append-only 存储 + 查询筛选。
- **编排埋点**：ChatOrchestrator 的 onTokens 调用时记录 UsageRecord（成本写入）。
- **审计埋点**：认证、Agent 调用、配置变更等关键事件记审计日志。

### 不做（YAGNI）
- Redis/DB 持久化（二期；一期 InMemory）。
- 告警通道（Webhook/邮件，二期）。
- admin-console REST 端点（add-admin-console change）。
- OTel/Prometheus 上报（add-observability 已做 token 指标）。
- 部门分摊、用户级预算、审批（二期）。

## 验收标准
1. domain UsageRecord/CostRepository + Budget（零框架）。
2. domain AuditLog/AuditEvent/AuditRepository（零框架）。
3. InMemory 实现端口，单测覆盖（成本聚合 + 预算 + 审计 append-only）。
4. 覆盖率 ≥80%，domain 未改（除新增类型/端口）。
5. `mvn clean test` 全绿。

## 关联文档
- spec §21 成本中心、§22 审计日志
- 前置：编排核心（ChatOrchestrator 已有 ObservabilityHooks token 埋点）

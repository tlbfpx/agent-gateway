# Proposal: 开放 API + 示例 Agent（add-openapi-and-example-agent）

> **状态：✅ 已完成**（2026-08-14）。

## 变更概述

补齐开放 API 端点（spec §23：sessions/agents/models/health）+ 实现示例远程 Agent（`example-agent` 模块，A2A 兼容 HTTP+SSE 服务器），用于端到端联调验证编排→A2A 链路。

## 动机

1. **开放 API 补齐**：编排 change 只做了 `/v1/chat`；spec §23 还需 sessions/agents/models/health 端点供程序化集成。
2. **示例 Agent**：编排→ToolPort→A2A 链路目前只经单元测试（mock ToolPort）验证。示例 Agent 提供真实 A2A SSE 服务器，端到端联调验证网关能真正调用远程 Agent。

## What / 范围

### 做
- **开放 API 端点**（gateway-interfaces）：
  - `POST /v1/sessions` 创建会话、`GET /v1/sessions/{id}` 详情、`GET /v1/sessions/{id}/messages` 历史分页
  - `GET /v1/agents` Agent 目录（AgentCardPort.snapshot，按授权过滤）、`GET /v1/models` 模型列表
  - `GET /v1/health` 健康检查
- **example-agent 模块**（新增，独立 Spring Boot 应用）：
  - 最小 A2A 兼容服务器：`POST /a2a/invoke/{agentName}` 接收 JSON-RPC，返回 SSE 流（chunk→done）。
  - 一个示例 Agent（如 `echo-agent`：回显输入 + 标记）。
  - 启动时向 Nacos 注册 AgentCard（AiService.releaseAgentCard）——条件（需 nacos.addr）。
  - 可独立运行（`mvn spring-boot:run`），供网关 A2A 调用联调。

### 不做（YAGNI）
- OpenAPI 3.0 导出（`/v1/openapi.json`）：二期（springdoc 适配 Boot4 待验）。
- 多语言 SDK：二期。
- 批量导入导出、Webhook：二期。
- example-agent 的复杂业务逻辑：仅最小回显，验证协议。

## 验收标准
1. `/v1/sessions`（POST/GET）、`/v1/agents`、`/v1/models`、`/v1/health` 端点实现 + 单测。
2. `example-agent` 模块：可编译、可独立启动（监听端口）、A2A 端点返回 SSE 流。
3. example-agent 启动向 Nacos 注册 AgentCard（有 nacos.addr 时）。
4. 覆盖率 ≥80%（gateway 端点；example-agent 最小测试）。
5. `mvn clean test` 全绿。

## 关联文档
- spec §23 开放 API、§2 数据流、§4 AgentCard：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置：编排核心（ChatController/SessionRepository/AgentCardPort 已就绪）

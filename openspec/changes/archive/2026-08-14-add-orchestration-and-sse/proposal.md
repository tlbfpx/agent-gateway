# Proposal: 编排核心 + SSE 端点（add-orchestration-and-sse）

> **状态：✅ 已完成**（2026-08-14）。验收见末尾「实现结果」。

## 变更概述

实现编排核心（gateway-application）+ SSE 端点（gateway-interfaces），把 ChatClientPort/ToolPort/AgentCardPort/SessionRepository/Authenticator/AuthorizationService 串成端到端流式会话。spec §1.2/§2。这是网关真正「能跑」的最后一块。

## 动机

前置 5 个 change（multi-model/a2a/session-store/auth + foundation）提供了所有 domain 端口的 infra 实现，但无人**编排**它们。本 change 实现编排循环：用户消息 → 认证 → 加载会话 → 注入工具（AgentCard → ToolDescriptor）→ LLM 生成 → 若 ToolCall 则调 Agent → 结果回填 LLM → 流式输出 → 持久化。

## What / 范围

### 做
- **gateway-application**：
  - `ChatOrchestrator`（核心）：编排循环（spec §2.1）。接收 ChatRequest（sessionId/prompt/model），返回 `Flow.Publisher<ChatStreamEvent>`（流式事件，对内）。
    - 认证（Authenticator）→ 加载会话（SessionRepository）→ ContextWindow 裁剪 → 构造工具集（AgentCardPort.snapshot → AuthorizationService 过滤 → ToolDescriptor）→ LlmSession.generate → ToolCall 循环（invoke ToolPort → 结果回填 → 再 generate）→ 流式透传。
    - 一期：单轮 + 串行多 tool_call（一轮内 LLM 返回多个 ToolCall 时顺序执行）；并行 fan-out 留二期（复杂度）。
  - `ModelSelector`（§5.5.4）：会话级模型 + 请求级覆盖 + 默认兜底。
- **gateway-interfaces**：
  - `ChatController`：`POST /v1/chat`（非流式）+ `POST /v1/chat/stream`（SSE 流式，spec §8.2 event 约定）。
  - 认证 filter（X-API-Key → Authenticator）。
  - 全局异常处理（AuthenticationException→401 / AuthorizationException→403）。
- **流式 SSE 协议**（spec §8.2）：`event:chunk/done/error/abort`。

### 不做（YAGNI / 二期）
- **并行多 Agent fan-out**（§2.2）：一期串行。
- **限流**（§8.3）：独立关注，后续接入层 change。
- **OTel 上报**（§7）：add-observability change。
- **前端**（§12）：独立 change。

## 验收标准
1. `POST /v1/chat/stream` 端到端：API Key 认证 → 流式对话 → 单 Agent 调用 → SSE 输出。集成测试（mock LlmSession）。
2. `POST /v1/chat` 非流式：返回完整回答。
3. 认证失败 401、授权失败 403、模型不存在 400。
4. 会话历史持久化（SessionRepository），多轮上下文。
5. 能力降级（用户模型缺 FC 时 failover）—— 已在 ChatClientPortImpl 实现，编排透传。
6. 覆盖率 ≥80%（业务逻辑），domain 未改。
7. `mvn clean test` 全绿。

## 关联文档
- spec §1.2/§2/§5.5.4/§8.2：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置：foundation/multi-model/a2a/session-store/auth（全部已并入 master）
- 本 change design：同目录

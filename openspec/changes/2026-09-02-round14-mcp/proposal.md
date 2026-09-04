# Proposal: MCP 协议接入（round14-mcp）

> **状态**：Round 14 #1 · 平台化补强 · 协议兼容(竞品矩阵第 11 行)
> **来源**：竞品分析报告 §六 A 协议兼容 + Round 13 报告 §九 R14+ 候选
> **借鉴**：Anthropic Model Context Protocol (2024-11, 2025-06 streamable HTTP)

## 动机

agent-gateway 已有 **A2A**(Agent-to-Agent)协议(2024)适配器;
**MCP**(Model Context Protocol)由 Anthropic 2024-11 提出,
2025-06 加 streamable HTTP,2026 已成 LLM 工具调用事实标准:

- Claude Desktop / Cursor / Continue 全部内置 MCP client
- 数千社区 server 提供工具/资源/提示
- 与 A2A 互补:A2A 是 Agent 间协议,MCP 是 Agent ↔ Tool 协议

agent-gateway 当前 ❌ → 补 MCP 后 ✅ → 竞品对照表 8/3/0。

## What

### 后端

**domain** (`gateway-domain/mcp/`)
- `McpServer` record —— id / name / description / endpoint / transport(http|stdio) / capabilities
- `McpTool` record —— name / description / inputSchema (JSON Schema)
- `McpToolCall` record —— name / arguments / sessionId / requestId
- `McpToolResult` record —— content / isError / metadata
- `McpPort` Port —— listServers / listTools(serverId) / callTool(call) / initialize(serverId)

**application** (`gateway-application/mcp/`)
- `McpService` —— orchestrate:listTools → delegate to ChatOrchestrator tool port → return result
- `McpJsonRpcHandler` —— JSON-RPC 2.0 dispatch:initialize / tools/list / tools/call

**interfaces** (`gateway-interfaces/mcp/`)
- `McpController` —— POST /v1/mcp (JSON-RPC 2.0 single endpoint)
- `McpStreamController` —— GET /v1/mcp/sse (SSE stream for long-running tools)

**persistence** (`gateway-infra-persistence/mcp/`)
- `InMemoryMcpServerRepository` (P0) + 注册 sample servers

### 协议实现要点

**JSON-RPC 2.0 请求**:
```json
{ "jsonrpc":"2.0", "id":"1", "method":"tools/call", "params":{ "name":"...", "arguments":{...} } }
```

**响应(success)**:
```json
{ "jsonrpc":"2.0", "id":"1", "result":{ "content":[...], "isError":false } }
```

**响应(error)**:
```json
{ "jsonrpc":"2.0", "id":"1", "error":{ "code":-32600, "message":"..." } }
```

### 安全与限制

- API Key 鉴权(X-API-Key,与 chat 链路一致)
- tools/call 限制 timeout 30s
- arguments 大小限制 1MB
- R15 加 OAuth 2.1(2025-06 spec 推荐)

## Non-goals

- 不做 stdio transport(只 HTTP+SSE,与 A2A 对齐)
- 不做 MCP Prompts/Resources(只 Tools;主流用法)
- 不做 SSE 长连接客户端封装(只 server 端)

## 验收

- domain + application + interfaces 完整
- JSON-RPC 2.0 dispatch 三方法:initialize / tools/list / tools/call
- 错误码:-32700(parse)/ -32600(invalid)/ -32601(method)/ -32602(params)/ -32603(internal)
- 单测覆盖
- verify.sh 全绿

## 风险

| 风险 | 缓解 |
|---|---|
| 工具调用超时 | timeout 30s + ToolEvent.Error 回传 |
| MCP spec 演进 | 版本化 capability negotiation(`protocolVersion` 字段) |
| Server 注册安全 | P0 静态注册;R15 接 OAuth discovery |

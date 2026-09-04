# Round 18 #1 报告 — MCP 转发客户端

> 日期：2026-09-03 · 主攻：**R18 #1 MCP 转发客户端**
> 来源：R15 #1 留下的 stub + 用户决策
> 借鉴：MCP JSON-RPC 2.0 规范 / JDK HttpClient

---

## 一、本轮目标与切片

R15 #1 实现了 agent-gateway 作为 MCP **server** 暴露 JSON-RPC 端点;
本轮补齐 agent-gateway 作为 MCP **client** 调远端 server。

真实生产价值:agent-gateway 集成第三方 MCP server(Anthropic、Stripe、自建)统一代理。

## 二、产出

| 文件 | 用途 |
|---|---|
| `McpRemoteClient.java` | JSON-RPC 2.0 over HTTP 客户端 |
| `JsonReader.java` | 极简 JSON parser(自实现,P0) |
| `McpRemoteClientTest.java` | 7 E2E 测(JDK HttpServer 模拟远端) |

**累计 7 用例全绿**;verify.sh 11 模块 + 依赖方向全绿 ✅

## 三、亮点

### 1. 零依赖
JDK 11+ `java.net.http.HttpClient` + 自实现 JSON 解析;
R18+1 可换 Jackson 提升解析能力。

### 2. JSON-RPC 2.0 严格
- request:`{jsonrpc, id, method, params}`
- response success:`{jsonrpc, id, result}`
- response error:`{jsonrpc, id, error: {code, message}}`
- 错误码:ParseError(-32700) / InvalidRequest(-32600) / MethodNotFound(-32601) / InvalidParams(-32602) / InternalError(-32603)

### 3. 三方法 + 同步语义
```java
Map<String, Object> initialize(String endpoint, Duration timeout);
Map<String, Object> listTools(String endpoint, String serverId, Duration timeout);
McpToolResult callTool(String endpoint, McpToolCall call, Duration timeout);
```

### 4. 远端 error 优雅处理
```java
if (resp.containsKey("error")) {
    return McpToolResult.error("remote error: " + err.get("message"));
}
```
不抛异常,标记 isError,便于上层做重试/降级。

## 四、API 速查

```bash
# 1. configure 远端 MCP server 注册(McpServer.endpoint 设为远端 URL)
POST /v1/mcp/servers
  body: { id: "anthropic-1", name: "Anthropic", endpoint: "https://mcp.anthropic.com/v1" }

# 2. list tools(转发到远端)
GET /v1/mcp/servers/anthropic-1/tools

# 3. call tool(转发到远端)
POST /v1/mcp/test
  body: { path: "/tools/call", method: "POST", body: { serverId: "anthropic-1", name: "web_search", arguments: { query: "AI gateway" } } }
```

## 五、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-infra-persistence -am test` | ✅ 7/7 |
| `./verify.sh` | ✅ 11 模块 + 依赖方向全绿 |

## 六、评分

| 维度 | R17 #2 末 | R18 #1 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 106 | **106** |
| 产品完整度 | 122 | **123**(+1:agent-gateway 可作 MCP client 调远端) |

## 七、决策点

- **A**：接受 R18 #1 + 启动 R18 #2(SSO / Chicory / MCP server 端)
- **B**：R18 收官报告 + CronDelete 终止
- **C**：跳过 R18,R19 新主题

R18 候选:
- **R18 #2 SSO/OIDC** —— AdminAuthService 集成 OAuth2.1(Spring Security 6 重构)
- **R18 #2 Chicory Wasm** —— 替换 R15 #1 Java SPI 为真实 Wasm 运行时
- **R18 #2 MCP server 端** —— 当前 R15 #1 模拟 server,R18+2 接 SSE 流推送

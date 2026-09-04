# Design: A2A 协议客户端 + Nacos 发现（add-a2a-and-discovery）

> 本 change 特有技术决策。详细 step 见后续 `writing-plans`。
>
> **注**：本 design 已据 2026-08-13 Nacos A2A Spike（见 `docs/superpowers/spike/2026-08-13-nacos-a2a-compat-report.md`）对齐实测，nacos-client 3.3.0-BETA 内置完整 A2A API（`AiService`/`NacosAgentCardCacheHolder`/`AbstractNacosAgentCardListener`），简化了原自建缓存/推送方案。

## 1. A2A 客户端实现 ToolPort

### 1.1 协议适配
domain `ToolPort`（已定稿，不得改）：
```java
Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx);
// ToolEvent sealed: Delta(content) / Complete(fullResult) / Error(code,message)
```
A2A 协议 = JSON-RPC over HTTP+SSE。映射：

| A2A Event | ToolEvent |
|-----------|-----------|
| delta chunk | `Delta(content)` |
| done | `Complete(fullResult)` |
| error | `Error(code, message)` |
| SSE 中断 | `Error("A2A_STREAM_INTERRUPTED", ...)` |
| 超时 | `Error("A2A_TIMEOUT", ...)`（不重试，防雪崩） |
| 非 200 HTTP | `Error("A2A_HTTP_ERROR", ...)` |

### 1.2 SSE→Flow 适配器（关键）
domain 用 JDK `Flow.Publisher`（零框架），HTTP/SSE 客户端返回 Reactor `Flux` 或 InputStream。infra 写适配器（foundation design.md 预告的「infra 要写 Flow↔流式适配器」）：
- 若选 WebClient（WebFlux）：`Flux<String>` → `Flow.Publisher<ToolEvent>`（SubmissionPublisher 或自定义 Flow.Subscription，传递背压）。
- 若选 java.net.http：手写 SSE 解析 + SubmissionPublisher。

技术选型由 Spike 决定（Reactor 生态成熟 vs JDK 原生零依赖；infra 允许引 Reactor）。

### 1.3 超时/重试/降级
| 场景 | 处理 |
|------|------|
| 超时（`a2a.timeout=30s`） | 取消 + Error，不重试（工具调用对实时性敏感） |
| 连接失败 | 重试 1 次（幂等）→ Error("A2A_UNREACHABLE") |
| Agent 业务错误 | 透传为 Error(agentCode, agentMsg) |
| Agent 过载（429） | Error("A2A_RATE_LIMITED")，LLM 决定重试 |

## 2. Nacos 发现实现 AgentCardPort

### 2.1 推送优先 + 定时拉取兜底（spec §4.1）

**实测发现（Spike）**：nacos-client 3.3.0-BETA **内置**完整 A2A 支持：
- `com.alibaba.nacos.api.ai.AiService` + `A2aService` 接口
- `com.alibaba.nacos.client.ai.NacosAiService` 实现
- **`NacosAgentCardCacheHolder`**（内置 AgentCard 缓存）
- **`AbstractNacosAgentCardListener`**（内置推送监听器，接收 `AgentCardChangedEvent`）
- 完整 `com.alibaba.nacos.api.ai.model.a2a.*`：`AgentCard`/`AgentSkill`/`AgentCapabilities`/`AgentEndpoint`/`AgentCardVersionInfo`

**实现策略**：优先复用 Nacos 内置机制，**YAGNI**（仅在内置不足时自建）：
```java
NacosAgentCardPort implements AgentCardPort {
  // 优先：复用 Nacos 内置 NacosAgentCardCacheHolder（缓存）
  // 优先：复用 AbstractNacosAgentCardListener（推送）
  // domain.snapshot() → 适配到内置缓存（Nacos AgentCard → domain AgentCard）
  // domain.watch() → SubmissionPublisher 包装内置监听器事件
  
  // 兜底：定时拉取（防推送丢失）
  @Scheduled(fixedRate = 60s) 全量拉取 → 刷新内置缓存
  
  // 降级：Nacos 不可达时本地缓存继续服务
}
```

**模型映射**（需 mapper）：
| Nacos `model.a2a.AgentCard` | domain `AgentCard` |
|----------------------------|-------------------|
| name | name |
| description | description |
| skills | skills (List<String>) |
| inputSchema（JSON 字符串）| inputSchema（String） |
| outputSchema（JSON 字符串）| outputSchema（String） |
| version | version |
| endpoints 非 empty → available | available |

**定时拉取兜底保留**：TTL 30s < 拉取 60s，保证推送丢失时能恢复。

### 2.2 Nacos 不可达降级（spec §8.4）
| 场景 | 处理 |
|------|------|
| 启动时不可达 | 空缓存 + 告警 + 拒绝启动（配置一致性） |
| 运行时不可达 | 本地缓存继续服务（上次快照），告警 + 指标 `nacos.unreachable` |
| 推送失败 | 重试 3 次指数退避 → 降为纯拉取 |
| Nacos 恢复 | 重新订阅 + 全量刷新 |

## 3. 依赖决策
| 模块 | 依赖 | 版本 | 用途 |
|------|------|------|------|
| infra-nacos | nacos-client | 3.3.0-BETA（或 3.2.3 稳定版） | A2A Registry API（内置 AiService/缓存/监听器） |
| infra-a2a | spring-boot-starter-webflux（或 java.net.http） | — | SSE 客户端 |
| infra-nacos | caffeine | — | 可选兜底缓存（优先用内置 NacosAgentCardCacheHolder） |

**版本说明**：
- **3.3.0-BETA**（推荐开发期）：Maven Central HTTP 200，含最新 A2A API
- **3.2.3**（生产期候选）：稳定版，需实现期权衡 BETA 风险
- **3.3.0 正式版**：Maven Central 404 不存在（Spike 验证）

**依赖冲突（Spike 已验证）**：
- ✅ Boot 4.0 无冲突：nacos-client 3.3.0-BETA 通过 `mvn dependency:tree`
- ✅ Jackson 2.20.1 向下兼容 Boot 4.0 管理的 2.17.x
- ✅ nacos-client 3.x 移除 Netty 强依赖，改用 Apache HttpComponents 5，规避 Netty 版本冲突

**不推荐 SAA A2A 封装**（1.0.0.4 版本落后，功能简单），直接使用 nacos-client 内置 `AiService`。

## 4. 错误映射
A2A → ToolEvent.Error：连接失败(A2A_CONNECTION_FAILED,可重试1次) / 404(A2A_AGENT_NOT_FOUND,不重试) / 429(A2A_RATE_LIMITED) / 5xx(A2A_SERVER_ERROR,重试1次) / SSE中断(A2A_STREAM_INTERRUPTED) / 超时(A2A_TIMEOUT,不重试) / Agent业务错误(透传)。
Nacos → 降级：见 §2.2。

## 5. 测试策略
- **单元**：内置缓存/监听器适配、mapper（Nacos → domain）、推送广播、拉取兜底、Nacos 降级；SSE→Flow 适配、Event→ToolEvent 映射、超时重试。
- **集成**：WireMock 模拟 A2A SSE 响应；**testcontainers GenericContainer + Nacos 官方镜像**（Spike 验证无专用 testcontainers-nacos 模块）。
- **流式专项**：chunk 顺序、首 token 延迟、中途错误（已发 Delta 不撤回）、连接中断 onComplete+Error、多订阅者隔离。
- **并发专项**：多线程并发 invoke 无竞态。
- **覆盖率**：infra ≥80%（domain 90% 已完成）。

## 6. 与 domain 端口对接约束
严格遵循 add-foundation-skeleton 定稿签名（不得为适配改 domain）。所有适配（SSE→Flow、Nacos→AgentCard）封装在 infra 内部。

# 公司 Agent 通用网关 — 设计文档

- **状态**：已评审待用户确认
- **日期**：2026-08-12
- **作者**：brainstorming 会话产出
- **范围**：完整生产级设计（含一个示例远程 Agent）
- **协同规范**：本项目的实现与后续工作遵循 `AGENTS.md`（多 Agent 协同工作规范：默认并行、按模块切分、主线整合、多轮评审）。本 spec 的 §14-§29 即由 5 个子代理并行起草、4 轮评审产出。

---

## 0. 概述

构建公司级 Agent 通用网关：所有用户/系统统一与网关会话，网关作为**智能编排器**，通过 **A2A 协议**调用注册在 **Nacos** 的远程 Agent；集成 **spring-ai-alibaba-admin** 进行可观测监控。

### 0.1 关键技术决策

| # | 维度 | 决策 |
|---|---|---|
| 1 | 版本基线 | Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1（基于 Spring AI 2.0.0-M1，里程碑版，接受风险，见 §11） |
| 2 | 网关角色 | 智能编排器，内置 LLM |
| 3 | Agent 路由 | 动态 AgentCard + Function Calling |
| 4 | 会话形态 | 流式 SSE + 多轮会话 |
| 5 | 认证鉴权 | SSO + API Key 双通道，多租户，Agent 级 + 模型级 RBAC |
| 6 | 监控 | 独立 spring-ai-alibaba-admin + OTel |
| 7 | 模型接入 | 可配置多模型（Qwen/GLM/DeepSeek/MiniMax 等），用户选定生效 |
| 8 | 本期范围 | 完整生产级设计，含一个示例远程 Agent |

### 0.2 架构方案选型

选定 **方案 A：编排器直连 A2A**（备选 B 网关+独立 Router、C 事件驱动均因偏离单一入口定位或与流式会话错配而否决）。

---

## 1. 系统架构与组件边界

### 1.1 部署拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│                       公司内网用户 / 调用方                        │
│            (浏览器 Chat UI · 其他业务系统 · 脚本)                  │
└──────────────┬──────────────────────────────┬───────────────────┘
           SSE/HTTP                      API Key/HTTP
               │                              │
               ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Agent Gateway（智能编排网关）                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │ 接入层    │ │ 编排核心  │ │ A2A 客户端│ │ 会话存储  │            │
│  │ Auth/限流 │ │ LLM+工具 │ │ Nacos发现 │ │ 历史上下文│            │
│  └──────────┘ └──────────┘ └─────┬────┘ └──────────┘            │
│      Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1               │
└──────────────────────────────────┼──────────────────────────────┘
                                   │ A2A (JSON-RPC over HTTP+SSE)
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
┌──────────────┐          ┌──────────────┐          ┌──────────────────┐
│ 远程 Agent A │          │ 远程 Agent B │          │ 示例 Agent(本期) │
│ (HR/财务等)  │          │ (业务团队)   │          │ SAA A2A Server   │
└──────┬───────┘          └──────┬───────┘          └────────┬─────────┘
       │ 注册 AgentCard          │ 注册                       │ 注册
       └────────────┬────────────┴────────────────────────────┘
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│              Nacos 3.x（A2A Registry + 配置中心）                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│        spring-ai-alibaba-admin（独立监控应用）                    │
│   ◀──── OTel Trace / Metrics（网关上报，远程 Agent 可选上报）     │
│         面板：调用量·延迟·token·成本·Agent命中·错误率              │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 六大逻辑组件（进程内模块边界）

| 组件 | 职责 | 对外接口 | 依赖 |
|---|---|---|---|
| **IngressLayer 接入层** | SSE/HTTP 端点、认证、限流、请求编排 | `POST /v1/chat`、`POST /v1/chat/stream` | AuthPolicy、RateLimiter |
| **Orchestrator 编排核心** | 持有 `ChatClient`，多轮对话，Function Calling 决策调用哪个 Agent | `orchestrate(sessionId, userMsg)` | ChatClientFactory、ToolRegistry、SessionStore |
| **ToolRegistry 工具注册表** | 从 Nacos 拉取 AgentCard，适配为 SAA `@Tool`/Function；监听变更热更新 | `refresh()`、`getToolsFor(tenant,user)` | NacosA2ADiscovery |
| **A2AClient 调用层** | 封装 A2A JSON-RPC（含流式 SSE 透传、超时、重试） | `invoke(agentCard, payload, stream)` | Nacos（服务实例发现） |
| **SessionStore 会话存储** | 多轮会话历史、上下文窗口管理、TTL | `load/save/append(sessionId)` | Redis（主）/ DB（持久） |
| **Observability 可观测** | OTel trace/metrics 上报、调用链关联用户/租户/Agent | SPI 钩子 | OTel SDK、Admin |

### 1.3 边界澄清

- 网关不实现业务 Agent 逻辑，只编排；业务能力由远程 Agent 提供。
- 网关不直连 LLM 厂商裸 API，统一经 Spring AI / Spring AI Alibaba，便于切换模型与统一计费/监控。
- 远程 Agent 是黑盒，网关只认 AgentCard 契约（name/description/skills/input-output schema）。
- 示例 Agent 作为本期交付物，验证端到端链路，本身也是 SAA A2A Server 的参考实现。

---

## 2. 核心数据流

### 2.1 流式会话主链路（单 Agent 调用）

```
用户        网关接入层      Orchestrator    ToolRegistry     A2AClient      远程Agent      Nacos
 │  POST /v1/chat/stream   │                 │                │              │            │
 │  (msg, sessionId)       │                 │                │              │            │
 ├────────────────────────▶│ 认证/限流/租户解析│                │              │            │
 │                         ├────────────────▶│ 加载会话历史     │              │            │
 │                         │                 │ (SessionStore) │              │            │
 │                         │                 ├──── ChatClient.prompt() ──────┤            │
 │                         │                 │  LLM 决策：需要调用 AgentX    │            │
 │                         │                 │  → 触发 @Tool("AgentX")       │            │
 │                         │                 │ resolveTool("AgentX")         │            │
 │                         │                 ├───────────────▶│ 查 AgentCard │            │
 │                         │                 │◀───────────────┤ (内存缓存)   │            │
 │                         │                 │ invoke(card, payload, stream) │            │
 │                         │                 ├───────────────▶│─────────────▶│ A2A JSON-RPC│
 │                         │                 │                 │              │ 按需查实例 │
 │                         │                 │                 │◀─流式 token─┤◀──────────▶│
 │  ◀──── SSE chunk ──────▶│◀──流式 token───┤◀──流式 token───┤              │            │
 │  ◀──── SSE chunk ──────▶│                 │  (调用结果回填给 LLM，继续生成) │            │
 │  ◀──── SSE [DONE] ─────▶│                 │ 持久化本轮到 SessionStore      │            │
```

关键点：
- LLM 的 Function Calling 驱动 A2A 调用：LLM 输出 `tool_call(AgentX, args)`，SAA 框架自动回调注册的 `@Tool`，进入 A2AClient。
- 流式透传：A2AClient 把远程 Agent 的 SSE 流逐块向上转发。LLM 拿到完整工具结果后继续流式生成最终回答。
- 会话历史：每轮结束后持久化（用户消息、tool_call、tool_result、assistant 回答），下一轮加载为上下文。

### 2.2 多 Agent 协作链路

LLM 可在一轮对话中串行或并行触发多个 tool_call：

```
LLM 输出: tool_calls = [AgentA(query), AgentB(query)]   ← 并行
                         │
         Orchestrator: 并发分发到 A2AClient
            ├── fork: AgentA 调用 ──┐
            └── fork: AgentB 调用 ──┴── join (收集所有结果)
                                          │
                          结果回填 LLM ───▶ 生成综合回答（流式）
```

协作策略（一期实现）：
- **并行 fan-out / join**：LLM 一次返回多个 tool_call 时，用 `CompletableFuture` 并发调用，全部完成后回填。
- **串行链式**：LLM 自行在多轮 tool_call 中决定下一步，框架天然支持。
- **上下文共享**：所有 tool_call 结果进入同一会话历史，LLM 据此综合。

一期不做：复杂的 DAG 工作流引擎、人工编排的固定多 Agent 流程——交给 LLM 自主编排。

### 2.3 AgentCard 热更新流

```
远程Agent启动 ──注册AgentCard──▶ Nacos
                                   │
              网关 ToolRegistry ◀──┤ (Nacos 推送/定时拉取)
                    │
        ① 新增 Agent → 自动注册为新 @Tool，下一轮可被 LLM 选中
        ② AgentCard 变更(描述/skills) → 刷新工具元信息
        ③ Agent 下线 → 标记 unavailable，LLM 不再选中；进行中的调用走降级
```

---

## 3. 领域模型与模块划分

采用 DDD + 洋葱/六边形架构，domain 零框架依赖，infra 实现 domain 定义的出站端口。

### 3.1 限界上下文

| 上下文 | 核心职责 | 关键聚合/实体 |
|---|---|---|
| **会话 (Session)** | 多轮对话生命周期、历史管理 | `Session`、`Message`、`ContextWindow` |
| **编排 (Orchestration)** | LLM 驱动的工具决策与流转 | `Conversation`、`ToolInvocation`、`ToolResult` |
| **Agent 注册发现 (Registry)** | AgentCard 的发现、缓存、工具适配 | `AgentCard`、`ToolAdapter`、`RegistryCache` |
| **A2A 调用 (Invocation)** | 远程调用的协议封装、流式、重试 | `A2ARequest`、`A2AResponse`、`InvocationSpan` |
| **身份与访问 (IAM)** | 认证、租户、RBAC 授权 | `User`、`Tenant`、`AgentGrant`、`AuthPrincipal`、`ModelGrant` |
| **模型接入 (Model)** | 多模型注册、选择、能力路由 | `ModelDef`、`ModelRegistry`、`ChatClientFactory` |
| **可观测 (Observability)** | trace/metrics/审计 | `CallRecord`、`MetricEvent`、`AuditLog` |

### 3.2 Maven 模块结构

```
agent-gateway/                          （父 pom，统一版本管理）
├── gateway-bootstrap/                  启动模块：main、配置装配、Dockerfile
├── gateway-api/                        对外契约：DTO、OpenAPI、错误码常量
├── gateway-domain/                     ★ 领域核心（纯逻辑，零框架依赖）
│   ├── session/                        Session/Message/ContextWindow
│   ├── orchestration/                  Conversation/ToolInvocation 接口
│   ├── registry/                       AgentCard/ToolAdapter 接口
│   ├── model/                          ModelDef/ModelRegistry 接口
│   ├── iam/                            AuthPrincipal/Tenant/权限模型
│   └── observability/                  CallRecord/MetricEvent 接口
├── gateway-application/                应用服务：用例编排，依赖 domain
│   ├── ChatOrchestrator                编排用例（核心）
│   ├── SessionService                  会话用例
│   ├── AgentToolRegistry               工具注册用例
│   └── ModelSelector                   模型选择用例
├── gateway-infra-a2a/                  A2A 协议适配实现
├── gateway-infra-nacos/                Nacos A2A 发现 + 配置中心适配
├── gateway-infra-llm/                  多 Provider ChatClient 装配（dashscope/zhipu/openai-compat/minimax）
├── gateway-infra-persistence/          Redis/DB 会话存储实现
├── gateway-infra-security/             SSO/API Key/RBAC 鉴权实现
├── gateway-infra-observability/        OTel/Admin 上报实现
├── gateway-interfaces/                 Web 控制器、SSE 端点、异常处理
└── example-agent/                      ★ 示例远程 Agent（SAA A2A Server）
```

依赖方向（严格单向）：

```
interfaces / bootstrap
       │
       ▼
application ──▶ domain
       │
       ▼
infra-* （实现 domain 定义的端口）
```

### 3.3 核心领域对象（实现期定稿）

> **以下为实现期定稿签名**（add-foundation-skeleton change 已落地，56 测试 + JaCoCo ≥90% 覆盖）。
> 已按「domain 严格零框架」原则修正 schema 类型与流式抽象（见 `openspec/changes/add-foundation-skeleton/design.md` 关键决策）：
> - schema 字段用 `String`（JSON 文本），不用 `JsonNode`（避免引入 Jackson）。JsonNode 解析在 `gateway-api`/infra 层。
> - 流式用 JDK 原生 `java.util.concurrent.Flow.Publisher`，不用 Reactor `Flux`。infra 实现时写 Flow↔Flux 适配器。
> - `ChatClientPort` 返回 domain 自己的 `LlmSession` 抽象，不依赖 Spring AI `ChatClient`。

```java
// gateway-domain/shared/  Identity 值对象（每个单独文件，共享 IdValidation.requireNonBlank）
public record UserId(String value) { public UserId { IdValidation.requireNonBlank(value); } }
public record TenantId(String value) { /* 同构 */ }
public record ModelId(String value) { /* 同构 */ }
public record SessionId(String value) { /* 同构 */ }
public record RoleId(String value) { /* 同构 */ }
public record ApiKeyId(String value) { /* 同构 */ }
public record AgentVersion(String value) { /* 同构；语义版本比较见 §4.4 */ }

// gateway-domain/session/
public record Session(SessionId id, TenantId tenant, UserId user,
                      ModelId model,                          // 用户选定的模型（§5.5.4）
                      Instant createdAt, Instant lastActiveAt,
                      List<Message> history) {
    public Session append(Message m) { /* 追加 + 对超大 ToolResult 瘦身（§5.3） */ }
}
public sealed interface Message
        permits UserMessage, AssistantMessage, ToolCallMessage, ToolResultMessage {}
// 子类型 public（各自独立文件），供编排层构造 + 持久层反序列化
// UserMessage(String content) / AssistantMessage(String content)
// ToolCallMessage(String agentName, String argsJson)
// ToolResultMessage(String agentName, String content, boolean slimmed)
// ContextWindow：注入 LLM 前的历史裁剪，一期=Token 截断（保最近 minKeep）

// gateway-domain/orchestration/  会话存储端口（add-session-store 新增）
public interface SessionRepository {
    Session load(SessionId id);                                  // 不存在返 null
    void save(Session session);                                   // 不可变替换
    List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit);  // 分页+租户隔离
    Session create(TenantId tenant, UserId user, ModelId model);  // 生成 SessionId
    void delete(SessionId id);
}

// gateway-domain/registry/
public record AgentCard(String name, String description, List<String> skills,
                        String inputSchema, String outputSchema,   // JSON 文本（零框架）
                        String version, boolean available,
                        String endpointUrl) {}   // 远程 Agent 调用地址；infra-nacos 从 Nacos AgentCard.endpoints 映射；null=地址未知不可调用

// gateway-domain/model/  （ModelDef 权威定义见 §17.2，含 endpoint/apiKeyRef）
public record ModelDef(ModelId id, String provider, String displayName, String endpoint,
                       String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                       BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                       boolean enabled, List<String> tenantScope) {
    public boolean supportsFunctionCalling() { return capabilities.contains(Capability.FUNCTION_CALLING); }
}
public enum Capability { FUNCTION_CALLING, VISION }   // 唯一定义处

// gateway-domain/orchestration/  （出站端口，JDK Flow，零框架）
public sealed interface ToolEvent permits ToolEvent.Delta, ToolEvent.Complete, ToolEvent.Error {
    record Delta(String content) implements ToolEvent {}
    record Complete(String fullResult) implements ToolEvent {}
    record Error(String code, String message) implements ToolEvent {}
}
public sealed interface LlmEvent permits LlmEvent.Delta, LlmEvent.ToolCall, LlmEvent.Complete {
    record Delta(String content) implements LlmEvent {}
    record ToolCall(String toolName, String argsJson) implements LlmEvent {}
    record Complete() implements LlmEvent {}
}
public record InvocationCtx(SessionId session, AuthPrincipal principal, String traceId) {}
public record ToolDescriptor(String name, String description, String inputSchemaJson) {}
public interface LlmSession {                                          // infra-llm 桥接 Spring AI ChatClient
    Flow.Publisher<LlmEvent> generate(String prompt, InvocationCtx ctx);
}
public interface ToolPort {                                            // 由 infra-a2a 实现
    Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx);
}
public interface AgentCardPort {                                       // 由 infra-nacos 实现
    List<AgentCard> snapshot();
    Flow.Publisher<List<AgentCard>> watch();
}
public interface ChatClientPort {                                      // 由 infra-llm 实现
    LlmSession sessionFor(ModelId model, List<ToolDescriptor> tools);
}

// gateway-domain/iam/
public record AuthPrincipal(UserId user, TenantId tenant,
                            Set<AgentGrant> agentGrants,
                            Set<ModelId> allowedModels,
                            AuthChannel channel /* SSO | API_KEY */) {
    public boolean canInvoke(String agentName) { /* agentGrants 含匹配 agentName */ }
    public boolean canUse(ModelId model) { /* allowedModels 包含 */ }
}
public record AgentGrant(String agentName, Set<String> allowedSkills) {}
public enum AuthChannel { SSO, API_KEY }
```

> 实现状态：domain 全部类型已实现 + 测试（line 100% / branch 90% / instruction 99.4%，56 tests）。
> 一期 AuthPrincipal 用扁平 grant 判定（Role/Permission 体系见 §19，后续 RBAC change 补）。

### 3.4 模块边界自检

每个模块：做什么、怎么用、依赖谁，均可不读内部即理解。端口定义在 domain，infra 可整体替换（如换 Consul、换 LLM Provider）而不影响 application 层。

---

## 4. Agent 注册发现与路由策略

### 4.1 AgentCard 发现与缓存

```
远程Agent 启动
    │ 1. 向 Nacos A2A Registry 注册 AgentCard
    ▼
Nacos A2A Registry ──(推送变更)──▶ gateway-infra-nacos
                                       │
                            AgentCardPort 实现
                                       │
                       ┌───────────────┼───────────────┐
                       ▼               ▼               ▼
                 ① 内存缓存        ② 定时拉取兜底   ③ 广播到 ToolRegistry
              (Caffeine, TTL 30s)   (每 60s 全量)    (Spring Event)
```

- 推送优先：Nacos Listener 实时感知 Agent 上下线与 AgentCard 变更。
- 定时拉取兜底：每 60s 全量拉取，防止推送丢失。
- 内存缓存：Caffeine TTL 30s，避免每次编排都查 Nacos。

### 4.2 AgentCard → LLM 工具适配

`ToolRegistry` 把每个 AgentCard 适配成 Spring AI `@Tool`/Function，动态注册到 `ChatClient`。

> **工具与模型的绑定点**：策略链过滤后的最终工具集，在 `ChatClientFactory.clientFor(modelId, tools)`（§5.5.3）处与用户选定模型绑定注入，而非在工具注册阶段。即工具注册（本节）只产生候选集，模型绑定发生在编排取用 ChatClient 时（见 §5.5.4/§5.5.6）。

```java
@Component
class AgentToolRegistry {
    private volatile Map<String, ToolDefinition> activeTools;

    @EventListener
    void onCardChanged(AgentCardChangedEvent e) { rebuild(e.snapshot()); }

    List<ToolDefinition> toolsFor(AuthPrincipal p) {
        return activeTools.values().stream()
            .filter(t -> p.canInvoke(t.agentName()))   // IAM 授权过滤
            .toList();
    }
}
```

工具元信息映射：

| AgentCard 字段 | 映射到工具 | 说明 |
|---|---|---|
| `name` | 工具名 | 全局唯一、稳定，LLM tool_call 标识 |
| `description` | 工具描述 | **最关键**——LLM 据此判断何时调用 |
| `skills` | 能力标签 | 辅助过滤，可拼入描述增强 LLM 理解 |
| `inputSchema` | 参数 JSON Schema | LLM 据此生成 tool_call 入参 |
| `version` | 元信息 | 多版本并存时选择依据 |
| `available=false` | 不注册 | 下线 Agent 对 LLM 不可见 |

### 4.3 路由策略层

LLM 默认自由选择工具，策略层在工具注入前做过滤与加权：

```
AgentCard 快照
     │
     ▼
┌─────────────────────────────────────┐
│  RoutingPolicy 链（责任链）          │
│  ① TenantScopeFilter  按租户可见性  │
│  ② RbacFilter         按用户授权    │
│  ③ PinRuleFilter      固定绑定优先  │
│     (意图/关键字 → 强制选某 Agent)   │
│  ④ BlocklistFilter    黑名单        │
│  ⑤ PriorityWeight     权重排序(二期)│
└─────────────────────────────────────┘
     │
     ▼
注入 ChatClient 的最终工具集
```

| 策略 | 作用 | 一期 |
|---|---|---|
| TenantScopeFilter | 多租户隔离 | ✅ |
| RbacFilter | 用户级 Agent 授权 | ✅ |
| PinRuleFilter | 关键场景强制绑定 | ✅ 配置化 |
| BlocklistFilter | 紧急下线 | ✅ |
| PriorityWeight | 同类多 Agent 优先级 | ⏳ 二期 |

策略规则存 Nacos 配置中心，热更新，无需重启。

### 4.4 多版本与同名 Agent

- 同名冲突：注册校验 `name` 全局唯一，冲突以先注册者为准并告警。
- 多版本并存：AgentCard 带 `version`，一期默认选最新可用版本（"最新" 判定口径：按语义版本号比较，无法解析时退化为注册时间戳最新者）；二期支持灰度（按租户/比例路由）。
- 多实例：同名同版本多实例由 Nacos A2A Registry 内置负载均衡处理，网关不感知实例细节。

### 4.5 LLM 选错/选不到的兜底

> **注册默认状态衔接**：远程 Agent 向 Nacos A2A Registry 注册后，网关侧的初始生命周期状态为 `PENDING_REVIEW`（待审核），经管理员审核通过才进入 `PUBLISHED` 对 LLM 可见（详见 §15.2 状态机、§15.3 审核流）。即「注册到 Nacos」≠「对用户/LLM 立即可见」。

| 场景 | 处理 |
|---|---|
| 没选任何工具直接回答 | 正常路径 |
| 选了被 RBAC 过滤的工具 | 框架拒绝该 tool_call，返回「无权限」给 LLM 重试 |
| 选中的 Agent 不可用 | 缓存淘汰；进行中调用走降级（§8） |
| 描述模糊致误选 | 监控「Agent 命中分布」（§7），运维侧优化描述 |

---

## 5. 会话与上下文管理

### 5.1 会话存储架构

```
┌─────────────────────────────────────────────────────────────┐
│                     SessionStore 端口 (domain)              │
└────────────────────────────┬────────────────────────────────┘
              ┌──────────────┴───────────────┐
              ▼                              ▼
   ┌───────────────────┐          ┌────────────────────┐
   │  Redis (热路径)    │          │  关系DB (冷持久)    │
   │ key: session:{id} │ 异步落盘  │  session 表        │
   │ val: 完整消息列表  │ ───────▶ │  message 表        │
   │ TTL: 24h 滑动续期  │          │  (审计/检索/分析)   │
   └───────────────────┘          └────────────────────┘
```

- Redis：编排热路径读写，毫秒级；List/Stream 结构存消息；TTL 24h 滑动续期。
- 关系 DB：长期持久、审计、按租户/用户检索；异步写入不阻塞热路径。
- 写入策略：Redis 同步写，DB 异步写。读只命中 Redis；冷会话按需从 DB 回源重建。

### 5.2 会话模型与生命周期

```java
Session { id, tenant, user, model, createdAt, lastActiveAt, messages: [...] }
```

状态机：

```
ACTIVE ──(24h 无活动)──▶ EXPIRED ──(归档)──▶ ARCHIVED
   │
   └──(用户/管理员关闭)──▶ CLOSED
```

- 会话 ID 由网关生成，后续请求携带。
- 多租户隔离：所有读写强制带 tenant 校验，防越权。
- 并发控制：同一 session 并发请求用 Redis 分布式锁/乐观版本号串行化编排。

### 5.3 上下文窗口管理

`ContextWindow` 在每次编排前裁剪历史：

| 策略 | 做法 | 一期 |
|---|---|---|
| **Token 预算截断** | 从最新向前保留，累计 token 接近上限（预留工具结果空间） | ✅ |
| **ToolResult 瘦身** | 大体积工具返回替换为摘要，完整结果存 DB | ✅ |
| **滚动摘要压缩** | 早期 N 轮压缩成摘要消息（小模型生成） | ⏳ 二期 |

```java
class ContextWindow {
    List<Message> fit(List<Message> history, int tokenBudget) {
        // 1. 累计 token；2. 超预算时先瘦身 toolResult，再压缩早期，最后截断；
        // 3. 始终保留系统提示 + 最近 K 轮原文
    }
}
```

### 5.4 流式下的会话一致性

- 用户消息在**请求开始时**入历史；assistant 回答与 tool_call/result 在**流结束后统一**入库（避免半成品历史与频繁写库）。
- 中断恢复：流中途断开，本轮 tool_call 标记 `aborted`，不写入成功历史，但记入审计日志。
- **Redis 故障窗口的数据一致性**：因 DB 为异步落盘，Redis 故障瞬间可能存在尚未落盘的最近消息（最多为一个异步批次窗口，默认 ≤ 5s）。补救措施：① Redis 开启 AOF 持久化（appendfsync everysec），将 RPO 收敛到 ≤ 1s；② Redis 故障期间，热会话恢复时优先以 DB 为准，对窗口内缺失的最近消息向用户明示「会话历史可能不完整」，并允许用户重发上一问；③ 不静默丢失——审计日志始终记录原始消息，可人工补录。详见 §8.4 降级策略。

---

## 5.5 模型接入与路由（用户选定模型）

### 5.5.1 设计目标

- 多模型可配置：Nacos 配置中心声明可用模型，新增/下线模型无需改代码、无需重启。
- 国内主流全覆盖：Qwen（通义千问/DashScope）、GLM（智谱）、DeepSeek、MiniMax。
- 用户选定生效：用户在会话中选定模型，网关用该模型完成编排。
- 统一抽象：所有模型经 Spring AI `ChatModel`/`ChatClient` 抽象，编排逻辑不感知具体厂商。

### 5.5.2 模型注册表（ModelRegistry）

模型定义放 Nacos 配置中心，热更新：

```yaml
gateway:
  models:
    - id: qwen-max
      provider: dashscope              # spring-ai-alibaba
      displayName: "通义千问 Max"
      endpoint: https://dashscope.aliyuncs.com
      apiKey: ${SECRET:DASHSCOPE_KEY}  # 密钥管理注入，不落明文
      capabilities: [function-calling, vision]
      contextWindow: 32000
      costPer1kIn: 0.04
      costPer1kOut: 0.12
      enabled: true
      tenantScope: [all]
    - id: glm-4-plus
      provider: zhipu                  # spring-ai-zhipuailm
      displayName: "智谱 GLM-4 Plus"
      ...
    - id: deepseek-v3
      provider: openai-compatible      # DeepSeek 兼容 OpenAI 协议
      endpoint: https://api.deepseek.com
      ...
    - id: minimax-abab7
      provider: minimax                # 或 openai-compatible
      ...
```

`ModelRegistry`：启动加载，监听 Nacos 变更热更新；暴露 `list(tenant)` / `get(id)`。

### 5.5.3 多 Provider 适配

| 模型 | Provider 标识 | 接入方式 |
|---|---|---|
| Qwen / 通义千问 | `dashscope` | `spring-ai-alibaba-starter-dashscope` |
| GLM / 智谱 | `zhipu` | `spring-ai-zhipuailm`（Spring AI 社区） |
| DeepSeek | `openai-compatible` | `spring-ai-openai` starter + 自定义 base-url |
| MiniMax | `minimax` 或 `openai-compatible` | `spring-ai-minimax` 或兼容协议 |

`ChatClientFactory`（infra）：按 `provider` 构造 `ChatModel` Bean，**缓存复用**（连接池，不每请求重建）；配置变更时失效重建。

```java
class ChatClientFactory {
    Map<ModelId, ChatClient> pool;
    ChatClient clientFor(ModelId id, List<ToolDefinition> tools) { /* 装配工具后返回 */ }
}
```

### 5.5.4 模型选择路由（用户选定生效）

选择粒度：会话级为主 + 请求级可覆盖。

```
请求进入
  ├─ 请求体带 modelId?  ──是──▶ 用请求级 modelId（仅本次）
  │     否
  ├─ 会话已绑定 modelId? ──是──▶ 用会话模型
  │     否
  └─ 用默认模型（租户/全局默认）
  ▼
ModelSelector.select(principal, sessionId, requestedModel)
  ├─ 校验：principal 是否有该模型授权（allowedModels）
  ├─ 校验：模型 enabled & 在租户可见范围
  ▼
返回 ModelId → Orchestrator → ChatClientFactory.clientFor() → 编排
```

会话记忆模型：选定后写入 `Session.model`（§5.2），后续轮次默认沿用，直到用户切换。前端在 Chat UI 提供模型下拉。

### 5.5.5 能力矩阵：Function Calling 与降级

**关键约束**：编排器的 A2A 调用由 LLM Function Calling 驱动（§2/§4）。模型是否支持 tool use 决定它能否触发 Agent 调用。

| 能力 | 说明 |
|---|---|
| `function-calling` | 支持工具调用 → 可正常编排、调用远程 Agent |
| 无该能力 | 只能纯问答，无法触发任何 Agent |

处理策略（用户选了不支持工具的模型，但问题需要调 Agent）：

- **一期实现：自动 failover（选项 A，唯一策略）**。本轮自动 failover 到 `orchestrator.fallbackToolModel`（一期交付的配置项，默认为带 function-calling 的模型，如 qwen-max），完成工具调用并向用户说明「已切换至 X 完成工具调用」。fallbackToolModel 本身必须具备 `function-calling` 能力，启动时校验，否则拒绝启动。
- **错误码 `GW-3001 模型不支持工具调用` 的语义**：该错误**仅在 failover 也失败时**触发——即 fallbackToolModel 不可用、或用户模型与 fallback 均不支持工具调用时，作为兜底返回。它是 failover 路径之后的最后一道反馈，而非默认拒绝路径。一期不实现「直接拒绝、要求用户手动切换」的选项 B 行为。

### 5.5.6 与其他章节的交互点

| 章节 | 补充约束 |
|---|---|
| §4 路由 | 工具注入不变；`ChatClientFactory.clientFor()` 在构造时把过滤后的工具集注入对应模型的 ChatClient |
| §6 RBAC | 授权扩展：`AgentGrant` 之外新增**模型级授权** `allowedModels`；默认全员可用基础模型，高级模型按授权 |
| §7 可观测 | 所有指标/trace span 增加 **`model` 标签**；成本按 `costPer1kIn/Out` 核算，可出「按模型/租户的成本报表」 |
| §8 限流成本 | token 预算可**按模型差异化**（贵模型更紧配额）；LLM 故障 failover 扩展为**跨模型 failover** |
| §9 测试 | 新增：多模型选择、模型切换、能力降级、配置热更新、未授权模型拦截 |

---

## 6. 认证鉴权与多租户

### 6.1 双通道认证

```
请求进入 gateway-interfaces
  │  AuthWebFilter (统一入口过滤器)
  │    ├─ 检测 channel
  │    ▼              ▼
  │  ┌─────────┐  ┌──────────┐
  │  │ SSO 通道 │  │ API Key  │
  │  │ OIDC/JWT│  │  通道     │
  │  └────┬────┘  └────┬─────┘
  │       │ 校验 IDP JWT  │ 校验网关签发 key (查 Redis/DB)
  │       ▼              ▼
  │  ┌──────────────────────────┐
  │  │   解析为 AuthPrincipal    │  ← 统一内部模型
  │  │ (user, tenant, grants,   │
  │  │  allowedModels, channel) │
  │  └────────────┬─────────────┘
  └───────────────┼─────────────────────────────────────────
                  ▼ 放入 SecurityContext，后续全程携带
```

| 通道 | 适用场景 | 凭证 | 身份来源 |
|---|---|---|---|
| **SSO** | 浏览器 Chat UI、人工用户 | 公司 IDP 签发的 OIDC JWT | IDP 用户目录 |
| **API Key** | 业务系统、脚本、服务间 | 网关签发 key（`X-API-Key`） | key 绑定的 service account |

- 一期实现：API Key 通道（自包含、易联调）。
- SSO 接公司 IDP：二期，但 domain 的 `Authenticator` 端口一期定义好，预留 OIDC 实现位。

### 6.2 多租户隔离

租户 = 部门/团队。隔离贯穿三层：

- 数据层：Redis key / DB 表均带 tenant_id，查询强制带条件。
- 会话层：sessionId 绑定 tenant，跨租户读取直接拒绝。
- Agent 层：AgentCard 可标记 tenantScope（全局/租户私有），路由层 TenantScopeFilter 过滤。

租户解析：API Key 通道取 key 绑定的 tenant；SSO 通道从 IDP token claim 映射。一个用户跨多租户：一期单租户/请求，二期支持切换。

### 6.3 Agent 级与模型级 RBAC

授权决策点在两处（纵深防御）：

```
① 工具注入时（RoutingPolicy.RbacFilter）
   principal.agentGrants 不含 AgentX → AgentX 不在工具集 → LLM 看不到
② A2A 调用前（二次校验）
   即使 LLM 误选/构造 tool_call，A2AClient 调用前再查一次 grants，无权限 → 拒绝
```

| 粒度 | 说明 | 一期 |
|---|---|---|
| Agent 级 | 能否调用某 Agent | ✅ |
| 模型级 | 能否使用某模型（贵模型限租户/用户） | ✅ |
| Skill 级 | Agent 内某能力子集 | ⏳ 二期 |
| 数据级 | 调用时参数约束（如只能查本部门） | ⏳ 二期（依赖 Agent 配合） |

授权数据来源：一期 Nacos 配置中心管理 `principal → grants/allowedModels`；二期接公司 IAM。

### 6.4 凭证传递与审计

- 下游 Agent 不重复认证：网关是统一入口，A2A 调用通过 mTLS 或网关签发的内部 token 证明来源，远程 Agent 信任网关透传的 principal（A2A metadata 透传用户/租户）。
- 审计：每次调用记录 `who(tenant+user) → which Agent/model → when → result`，写入审计日志（§7）。
- 敏感数据：API Key、SSO token 仅存网关内存/配置加密，日志脱敏。

---

## 7. 可观测性与 Admin 集成

### 7.1 可观测三支柱

```
┌─────────────── 网关进程内 ───────────────┐
│  ObservabilityHooks (SPI，贯穿各层)       │
│   ├─ Trace: 每次 chat/tool/a2a/model 产生 span │
│   ├─ Metrics: 计数器/直方图/仪表          │
│   └─ Audit: 结构化调用记录                │
└──────────┬──────────────────────────────┘
           │ OpenTelemetry SDK
      ┌────┴─────┬──────────┐
      ▼          ▼          ▼
  OTel Collector        审计日志库
  (Trace+Metrics)       (DB/ES)
      │
      ▼
┌──────────────────────────────────────────┐
│  spring-ai-alibaba-admin (独立应用)       │
│  聚合展示：面板/调用链/告警                │
└──────────────────────────────────────────┘
```

Admin 通过 OTel Collector 拉取数据，不与网关直连，解耦。

### 7.2 指标体系

所有指标带 `tenant/user/agent/model/channel` 标签：

| 类别 | 指标 | 类型 | 用途 |
|---|---|---|---|
| 流量 | `chat.requests` | Counter | 会话请求量 |
| | `agent.invocations` | Counter | 各 Agent 调用次数（**命中分布**） |
| 延迟 | `chat.latency` | Histogram | 端到端首 token / 完整响应 |
| | `agent.latency` | Histogram | 单 Agent A2A 调用延迟 |
| | `llm.latency` | Histogram | LLM 编排决策耗时 |
| 错误 | `chat.errors` | Counter | 按错误码分类 |
| | `agent.errors` | Counter | Agent 调用失败 |
| AI 成本 | `llm.tokens{in,out}` | Counter | token 消耗（按 model 标签核算成本） |
| | `llm.tool_calls` | Counter | 每轮 tool_call 次数 |
| 饱和度 | `a2a.inflight` | Gauge | 进行中 A2A 调用数 |
| | `session.active` | Gauge | 活跃会话数 |

### 7.3 调用链（Trace）

一次完整会话的 span 树：

```
chat.stream (root span)
 ├─ auth.authenticate
 ├─ session.load / session.save
 ├─ contextwindow.fit
 ├─ llm.invoke (model=qwen-max)          ← 用户选定模型
 │   └─ llm.tool_call(AgentX)
 ├─ routing.policy
 ├─ a2a.invoke(AgentX)
 │   ├─ nacos.resolve
 │   └─ http.send (A2A JSON-RPC, SSE 流)
 └─ llm.invoke (model=qwen-max)          ← 工具结果回填后最终生成
```

所有 span 携带 `traceId/sessionId/tenant/user/agent/model`，Admin 可按任一维度串联。流式调用 span 在 SSE 首块开始、末块结束。

### 7.4 Admin 集成方式

| 方面 | 方案 |
|---|---|
| 数据通道 | 网关 → OTel Collector → Admin 存储（或接公司已有 Prometheus/Tempo/Jaeger） |
| 上报协议 | OTLP（gRPC/HTTP），Spring Boot 4 + OTel starter 自动装配 |
| 远程 Agent 上报 | 一期仅网关上报；二期可选：远程 Agent 接 OTel，跨 Agent span 用 A2A metadata 透传 traceId |

关键看板：
1. 总览：QPS、延迟分位、错误率、活跃会话、token 成本曲线
2. Agent 健康度：每个 Agent 调用量、延迟、错误率、可用性、命中分布
3. 模型成本：按 model/tenant 的 token 消耗与成本报表
4. 调用链检索：按 sessionId/user/tenant 查单次会话全链路
5. 审计：who → which Agent/model → when → result

### 7.5 告警（一期最小集）

| 告警 | 条件 | 级别 |
|---|---|---|
| Agent 不可用 | `agent.available` 突然变 0 | P2 |
| 调用错误率飙升 | `agent.errors / agent.invocations` > 阈值 | P1 |
| LLM 编排异常 | 每轮 tool_call 次数异常高 | P2 |
| 成本异常 | token 消耗环比激增 | P2 |

---

## 8. 错误处理、限流降级与高可用

### 8.1 错误分类与处理矩阵

| 层 | 错误类型 | 处理策略 | 用户反馈 |
|---|---|---|---|
| 接入 | 认证失败 | 直接拒绝 401 | 「未认证」 |
| | 限流触发 | 429 + Retry-After | 「请求过频」 |
| | 请求体非法 | 400 + 错误码 | 指出字段错误 |
| 编排 | LLM 调用失败（超时/限流） | 重试指数退避 → 跨模型 failover → 友好失败 | 「服务繁忙」 |
| | 模型不支持工具调用但需调 Agent | 自动 failover 到 fallbackToolModel（§5.5.5）；failover 也失败才返回 GW-3001 | 「已切换至 X」/「暂无法调用 Agent」 |
| | LLM 死循环 tool_call | 超阈值熔断本轮 | 「处理超时」 |
| | 上下文超限 | ContextWindow 裁剪 | 透明 |
| A2A | Agent 不可达 | Nacos 重选实例 → 标记降级 → 告知 LLM 重试 | 透明重试或「该能力暂不可用」 |
| | Agent 超时 | 切断 + 有限重试 | 「响应超时」 |
| | Agent 返回错误 | 重试 → 降级 → 错误回填 LLM | LLM 据此解释 |
| | 协议错 | 不重试，记日志 | 「Agent 异常」 |
| 存储 | Redis 不可达 | 降级到只 DB 读 | 略慢但可用 |
| | DB 写失败 | 进死信队列补偿 | 无感（异步） |

统一错误码定义在 `gateway-api`，格式 `GW-XXXX`（如 `GW-1001 认证失败`、`GW-2003 Agent 超时`、`GW-3001 模型不支持工具调用`）。

### 8.2 流式下的错误传播

流式 SSE 一旦开始（已发 200 + 首 chunk），中途出错不能改 HTTP 状态码。约定：

```
正常:  event: chunk \n data: {...}      (逐 token)
完成:  event: done  \n data: {usage}
错误:  event: error \n data: {code, message, retryable}
中断:  event: abort \n data: {reason}    (用户断连/超时)
```

客户端收到 `error` event 后按 `retryable` 决定重试。已发出的 token 不撤回。

### 8.3 限流与配额

| 维度 | 算法 | 阈值来源 | 目的 |
|---|---|---|---|
| 租户级 QPS | 令牌桶 | Nacos 配置 | 防单租户压垮网关 |
| 用户级 QPS | 令牌桶 | 配置 | 公平性 |
| API Key 级 | 令牌桶 | key 作用域 | 服务间隔离 |
| Agent 级并发 | 并发计数器 | per-Agent 配置 | 保护下游 Agent |
| token 预算 | 日累计 | per-租户/用户/模型 | **成本控制**（可按模型差异化） |

实现：基于 Redis 的分布式限流（Lua 原子），进程内本地预限流减少 Redis 往返。超额反馈 429 + `Retry-After` + 明确超了哪个维度。

### 8.4 降级策略

```
LLM 厂商全挂   → 跨模型 failover（备选 provider）
Agent X 挂     → 工具标记 unavailable，LLM 改选其他 Agent 或告知用户
Redis 挂       → 会话降级为只 DB 读（慢但可用）；详见下方「Redis 故障数据窗口」
Nacos 挂       → 用本地缓存的 AgentCard 快照继续服务，期间不接受新 Agent
Admin 挂       → 不影响主链路（仅丢失指标）
```

**Redis 故障数据窗口**：Redis 为同步写、DB 为异步落盘，故 Redis 故障瞬间存在最多一个异步批次（默认 ≤ 5s）未落盘的最近消息。降级期间的处理：
- Redis 开启 AOF（appendfsync everysec），RPO ≤ 1s。
- 故障期间热会话以 DB 为准恢复，对窗口内缺失的最近消息向用户明示「会话历史可能不完整」并允许重发，不静默丢失（审计日志保留原始消息可补录）。
- 故障恢复后，DB 中较新的消息回填 Redis 缓存重建热路径。

核心原则：可观测层故障不影响业务；存储故障降级而非中断；LLM/Agent 故障尽力重试与告知；任何数据丢失窗口必须对用户透明、不静默。

### 8.5 高可用部署

```
                    负载均衡 (公司 LB, 需支持 SSE 长连接)
                    ┌───┴───┐
                    ▼       ▼
              网关实例1  网关实例2  ...  (无状态，水平扩展)
                    ├───┬───┤
                    ▼   ▼   ▼
              Redis(主从)  DB(主从)  Nacos(集群)  LLM(厂商)  Admin
```

| 要点 | 设计 |
|---|---|
| 网关无状态 | 会话/状态全在 Redis，实例任意挂、可弹性扩缩 |
| 会话亲和 | 不强求；同 sessionId 并发靠分布式锁串行，跨实例安全 |
| 流式与 LB | LB 需支持 SSE 长连接（不缓冲、超时设长）；或网关直连 + 客户端重连 |
| 优雅停机 | Spring Boot 4 graceful shutdown，等流式连接自然结束 |
| 健康检查 | `/health` 含 Nacos/Redis/DB/LLM 连通性，LB 据此摘除 |
| 配置热更新 | 路由策略、限流阈值、模型列表、Agent 黑白名单全在 Nacos，不停机生效 |

### 8.6 安全加固

| 项 | 措施 |
|---|---|
| 网关↔Agent | mTLS 或内部 token |
| 输入校验 | 请求体 schema 校验 + 防注入提示词约束 |
| 输出过滤 | 敏感信息脱敏（PII 过滤器二期） |
| 速率兜底 | 防暴力枚举 API Key（失败计数 + 临时封禁） |
| 审计不可篡改 | 审计日志 append-only |

### 8.7 容量与性能目标（SLO）

下列为目标维度，具体数值在压测后标定（TBD），但维度本身为一期须度量项：

| 维度 | 指标 | 一期目标（待压测标定） |
|---|---|---|
| 流式首 token 延迟 | `chat.latency.first_token` P99 | ≤ 1.5s（含 LLM 首 token） |
| 流式完整响应 | `chat.latency.total` P99 | ≤ 模型生成时长 + 500ms 网关开销 |
| 单 Agent A2A 调用 | `agent.latency` P99 | ≤ 3s（不含 Agent 内部处理） |
| 单实例并发流式会话 | `chat.inflight` | 待压测标定（前置假设：4C8G 单实例、单连接 token 速率 ~50/s，目标 ≥ 500；压测后按实际硬件修正） |
| 网关自身可用性 | 无状态实例滚动更新零中断 | 99.9% |
| 编排工具调用错误率 | `agent.errors / agent.invocations` | ≤ 1% |

---

## 9. 测试策略

### 9.1 测试金字塔

```
                    ┌───────────┐
                    │   E2E     │  5%  端到端：用户→网关→示例Agent 全链路流式
                    ├───────────┤
                    │ 集成测试   │ 20% 跨模块：编排+A2A+Nacos+Redis 真实组件
                    ├───────────┤
                    │ 切片测试   │ 30% 单层：Web层/持久层/安全切片
                    └───────────┘
                    │ 单元测试   │ 45% domain+application，纯逻辑，零框架
                    └───────────┘
```

domain 层追求 >90% 单元覆盖（纯逻辑易测），整体 >80%。

### 9.2 各模块测试策略

| 模块 | 测试类型 | 关键用例 | 依赖处理 |
|---|---|---|---|
| gateway-domain | 单元 | Session 追加/ContextWindow 裁剪/RBAC 决策/ModelSelector/错误码映射 | 无依赖 |
| gateway-application | 单元 | ChatOrchestrator 编排流、工具注入过滤、多 Agent fan-out/join | Mock 端口 |
| gateway-infra-a2a | 集成 | A2A JSON-RPC、SSE 流式解析、超时/重试、错误分类 | WireMock |
| gateway-infra-nacos | 集成 | AgentCard 注册/发现/推送/拉取兜底、热更新 | testcontainers Nacos |
| gateway-infra-llm | 集成 | 多 Provider ChatClient 构造、模型切换、能力降级、配置热更新 | WireMock 模拟各厂商 |
| gateway-infra-persistence | 集成 | Redis 读写/TTL、DB 异步落盘、冷会话回源 | testcontainers |
| gateway-infra-security | 单元+集成 | API Key 校验、RBAC 双层拦截、模型授权、多租户隔离 | Mock + testcontainers |
| gateway-interfaces | 切片(WebMvc) | SSE 端点、认证过滤、错误码响应、限流触发 | Mock application |
| example-agent | 集成 | 注册到 Nacos、响应 A2A 调用 | testcontainers Nacos |

### 9.3 关键场景用例（表驱动）

核心算法用表驱动（ContextWindow 裁剪、RBAC 决策矩阵、路由策略链、ModelSelector、错误分类、限流计数）：

```java
@ParameterizedTest @MethodSource("fitCases")
void shouldFitWithinTokenBudget(FitCase c) {
    List<Message> out = window.fit(c.history(), c.budget());
    assertThat(out).extracting(Message::id).containsExactlyElementsOf(c.expected());
}
static Stream<FitCase> fitCases() {
    return Stream.of(
        case("预算充足时保留全部"),
        case("超预算时先瘦身 toolResult"),
        case("仍超时压缩早期轮次"),
        case("极端情况至少保留系统提示+最近K轮")
    );
}
```

### 9.4 流式与并发专项测试

| 场景 | 测试要点 |
|---|---|
| 流式正常 | 逐 chunk 顺序、首 token 延迟、done event 收尾 |
| 流式中断 | 用户断连 → 后端及时停止、不泄漏资源、历史标记 aborted |
| 流式错误 | 中途 Agent 错误 → 发 error event、已发内容不撤回 |
| 多 Agent 并发 | fan-out 全部成功 / 部分失败 / 全失败 的 join 行为 |
| 会话并发 | 同 sessionId 并发请求被串行化、无历史混乱 |
| AgentCard 热更新 | 编排进行中收到下线事件 → 进行中调用走降级、新调用不选中 |
| 模型切换 | 会话中途切换模型、能力降级 failover、未授权模型拦截 |

并发安全用 `Awaitility` + `CompletableFuture` 构造并发，断言无竞态。

### 9.5 E2E 验证（联调闭环）

本期交付的端到端验证链路：

```
客户端 ──API Key──▶ 网关 ──A2A──▶ 示例 Agent (Nacos 注册)
   ▲                                      │
   │◀────── SSE 流式回答 ─────────────────┘
   └── 同时验证：多轮上下文 / AgentCard 热更新 / RBAC 拦截 / 模型切换 / Admin 指标可见
```

E2E 用 testcontainers 起完整环境（Nacos + Redis + 网关 + 示例 Agent），一条命令跑通，作为发布前门禁。

### 9.6 测试基建

| 工具 | 用途 |
|---|---|
| JUnit 5 | 主框架 |
| AssertJ | 流式断言（SSE chunk 序列） |
| Mockito | 端口 mock |
| WireMock | 模拟远程 Agent / LLM 厂商 |
| testcontainers | Nacos/Redis/Postgres/OTel Collector 真实组件 |
| Awaitility | 异步/流式断言 |
| Jacoco | 覆盖率（domain >90%，整体 >80%） |

---

## 10. 一期交付范围（MVP）与二期演进

### 10.1 一期交付（MVP — 生产可用的最小闭环）

**前置（0 阶段 Spike，启动前必做）**：
- §11 风险表中的 LLM starter 兼容性验证矩阵（DashScope/openai-compat/zhipu/minimax 在 Boot 4.0 + SAA 2.0.0-M1 + JDK 21 下逐个验证），不通过者确定兜底方案。

**范围说明**：一期管理后台模块（§16-§20）以**最小可用 CRUD 为主**——覆盖核心增删改查 + Nacos 下发热更新 + 基础 RBAC；高级特性（策略 dry-run 预览、配置版本 diff、模型灰度、组织多级树等）滞后到一期后段或二期，避免一期失控。

**代码范围**：
- 网关全部六层组件（接入/编排/工具注册/A2A/会话/可观测）的 MVP 实现
- 模型接入：DashScope(Qwen) + 至少一个 openai-compatible(DeepSeek)，能力降级自动 failover 到 `orchestrator.fallbackToolModel`（一期交付的配置项，启动时校验其具备 function-calling），含模型连通性测试（§17.3）
- 认证：API Key 通道（SSO 端口预留）
- 多租户 + Agent 级 RBAC + 模型级授权
- 流式 SSE + 多轮会话（Token 截断 + ToolResult 瘦身）
- OTel 上报 + Admin 集成 + 关键告警
- 限流五维度 + 跨模型 failover 降级
- 一个示例远程 Agent

**设计文档范围**：完整覆盖生产级特性（含二期特性的接口与演进路径）。

### 10.2 二期演进

- SSO 接公司 IDP（OIDC 实现 Authenticator）
- Skill 级 / 数据级 RBAC，授权接公司 IAM
- 模型灰度路由、滚动摘要压缩上下文
- Agent 多版本灰度、PriorityWeight 路由
- 远程 Agent 全链路 OTel 上报
- PII 输出过滤器
- 多租户切换

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Spring AI Alibaba 2.0.0-M1 为里程碑版** | API 可能变动、生产稳定性未知、Admin/A2A 模块可能未完全适配 | ① 隔离 SAA 依赖在 `gateway-infra-*`，domain 零依赖；② 关键路径写适配层，便于 SAA API 变动时只改 infra；③ 密切跟踪 SAA 2.x GA 进展，GA 后平滑升级；④ 一期灰度上线，监控先行 |
| Spring Boot 4.0 + JDK 17/21 生态 | 部分第三方 starter 可能未适配 Boot 4 | **0 阶段 Spike（启动前必做）**：逐个验证 starter 在 Boot 4.0 + SAA 2.0.0-M1 + JDK 21 下的兼容性（验证矩阵见 §11.1）。不兼容者立即切换 openai-compatible 兜底或自研轻量适配。该 Spike 是 §5.5 多模型方案落地的前置门，未通过不进入一期编码。 |
| LB 不支持 SSE 长连接 | 流式被中间层缓冲/打断 | 上线前验证公司 LB；必要时网关直连或换支持 SSE 的 LB |
| LLM Function Calling 稳定性 | 选错 Agent / 死循环 | 能力降级、tool_call 次数熔断、命中分布监控 |
| 远程 Agent 不可控 | 黑盒质量参差 | 超时、重试、降级、错误回填；Agent 健康度面板 |

### 11.1 Starter 兼容性验证矩阵（0 阶段 Spike）

在进入一期编码前，逐个验证以下 starter 在 **Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1 + JDK 21** 下的可用性，结果填入「验证状态」列：

| starter | 用途 | 验证状态 |
|---|---|---|
| `spring-ai-alibaba-starter-dashscope` | Qwen | ⏳ Spike 验证 |
| `spring-ai-openai`（兼容模式） | DeepSeek / MiniMax 兜底 | ⏳ Spike 验证 |
| `spring-ai-zhipuailm`（社区） | GLM | ⏳ Spike 验证，不兼容则走 openai-compatible |
| `spring-ai-minimax`（社区） | MiniMax | ⏳ Spike 验证，不兼容则走 openai-compatible |

验证不通过的，立即确定兜底方案（openai-compatible 协议或自研轻量适配），不得阻塞 §5.5 多模型方案。

---

## 12. 前端 Chat UI

### 12.1 整体页面布局

公司内部网关的前端是「会话为中心 + 左侧历史 + 右侧设置」的三栏式 Chat 应用：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  🤖 Agent 网关                                          user@dept ▾  │ ⚙ 退出  │
├───────────────┬───────────────────────────────────────────┬───────────────┤
│  + 新建会话    │  会话标题：上月销售分析                      │  当前会话设置  │
│  ───────────  │  ──────────────────────────────────────── │  ───────────  │
│  📁 今天       │                                            │  模型          │
│   • 销售分析   │  🧑 查上个月销售额，按地区拆分                │  ◉ qwen-max   │
│   • HR 政策    │     🤖 已调用：SalesAgent ▾  [查看详情]      │  ○ glm-4-plus │
│  📁 昨天       │     ┌──────────────────────────────┐       │  ○ deepseek-v3│
│   • 报销流程   │     │ 上月总销售额 ¥1.2M，各地区：    │       │  ○ minimax    │
│  📁 更早       │     │ - 华东 ¥520K  - 华南 ¥380K ▌  │←流式中│  可用 Agent    │
│  ───────      │     └──────────────────────────────┘       │  ☑ SalesAgent │
│  🔍 搜索会话   │                                            │  ☑ HRAgent    │
│               │  🧑 帮我导出 Excel                           │  ☐ FinanceBot │
│               │     🤖 正在调用 ExportAgent…                 │  会话信息      │
│               │                                            │  ID/token/消息 │
│               │  ─────────────────────────────────────────  │               │
│               │  ┌─────────────────────────────────────┐    │               │
│               │  │ [✏ 输入消息，Shift+Enter 换行…]  ▶  │    │               │
│               │  └─────────────────────────────────────┘    │               │
└───────────────┴───────────────────────────────────────────┴───────────────┘
   左栏 240px              中栏 会话区（自适应）                  右栏 280px
```

三栏职责：**左栏·会话列表**（新建/历史分组/搜索，小屏可折叠）；**中栏·会话区**（消息流、流式光标、输入框，永不折叠）；**右栏·会话设置**（模型选择、可用 Agent、会话元信息，默认折叠点 ⚙ 展开）。

### 12.2 关键交互元素

- **模型选择器**（顶栏 + 右栏双入口）：下拉显示模型名 + 能力标签 + 是否支持工具调用；切换立即生效写入会话（呼应 §5.5.4）；不支持工具的模型给视觉提示（灰色 [纯对话]），选它调 Agent 触发 failover。
- **工具调用卡片**（折叠式）：展示「网关调了哪个 Agent、传了什么、返回什么」，大结果折叠可展开。对应后端可观测透明性。
- **流式渲染**：token 逐字出现 + 闪烁光标 `▌`（对应 SSE `chunk`）；Markdown 实时渲染；收到 `done` 时光标消失并显示 token 用量。
- **错误/中断反馈**：`error` event → ⚠ 该能力暂不可用 [重试]；`abort` event → ⚠ 已中断 [继续]（对应 §8.2）。

### 12.3 状态管理与数据流

```
React 组件 → useChat (Vercel AI SDK)
   ├─ POST /v1/chat/stream (SSE) ← 网关
   ├─ 自动管理 messages 状态、流式增量追加
Zustand Store: sessionList / currentSession(model+agentGrants) / modelList
```

### 12.4 认证与部署

- 认证：一期 API Key（Header `X-API-Key`）；二期接 SSO（OIDC，token 走 cookie）——呼应 §6.1。
- 部署：前端构建为静态资源，由网关 `gateway-interfaces` 同源托管（免跨域），或独立 Nginx 反代。
- SSE 代理：浏览器 EventSource 直连网关 `/v1/chat/stream`，无需中间 BFF。

### 12.5 前端工程结构

```
agent-gateway-ui/                  （与后端同仓子目录）
├── src/
│   ├── components/chat/           ChatPanel·MessageList·Message·ToolCallCard·Composer
│   ├── components/sidebar/        SessionList·SessionItem·NewSessionButton
│   ├── components/settings/       ModelSelector·AgentList·SessionInfo
│   ├── hooks/                     useChatSession·useModels·useSessions
│   ├── stores/                    sessionStore·userStore (Zustand)
│   ├── lib/                       sseClient·apiClient·markdown
│   └── types/                     对齐 gateway-api DTO（OpenAPI 生成）
├── package.json                   ai @ai-sdk/react react-markdown ...
└── vite.config.ts
```

前后端契约：前端 `types/` 由网关 `gateway-api` 模块的 OpenAPI 规范自动生成，保证 DTO 一致。

---

## 13. 企业级平台模块全景

### 13.1 模块全景（按角色 × 优先级）

```
【终端用户区】
  ① 会话中心(§1-§9)   ② Agent 目录/市场(§14)   ③ Prompt 模板库 ◑   ④ 知识库/RAG(§27)◑   ⑤ 个人收藏 ◑

【管理后台区】
  ⑥ 租户与组织(§16)★   ⑦ Agent 生命周期(§15)★   ⑧ 模型管理(§17)★   ⑨ API Key(§18)★
  ⑩ RBAC 权限(§19)★   ⑪ 成本中心(§21)★         ⑫ 审计日志(§22)★   ⑬ 内容审核(§28)◑

【开发者区】 ⑭ 开发者控制台(§26)◑  ⑮ Agent Playground(§26)◑  ⑯ AgentCard 编辑器(§26)◑

【运维区】 ⑰ 监控大盘(§7)  ⑱ 告警管理◑  ⑲ 配置中心可视化(§20)  ⑳ 工作流编排(§29)◑

【集成/生态区】 ㉑ 开放 API(§23)  ㉒ 多渠道 IM(§24)◑  ㉓ Webhook/事件(§25)◑  ㉔ 数据导出◑

图例：✅ 已设计  ★ 一期MVP  ◑ 二期/三期演进
```

### 13.2 实现优先级分层（设计全覆盖，实现分阶段）

| 阶段 | 模块 |
|---|---|
| **一期 MVP** | ① 会话、② Agent 目录、⑥⑦⑧⑨⑩ 管理(租户/Agent/模型/APIKey/RBAC)、⑪ 成本、⑫ 审计、⑲ 配置中心、㉑ 开放 API |
| **二期** | ⑭⑮⑯ 开发者区、⑱ 告警、㉒ IM 多渠道、㉓ Webhook、㉔ 数据导出 |
| **三期** | ④ 知识库/RAG、⑬ 内容审核、⑳ 工作流编排 |

### 13.3 平台分层架构（更新自 §1）

从「网关单应用」演进为「网关核心 + 多个领域服务」。一期采用**模块化单体**（Modular Monolith）——领域服务作为 gateway 内不同 package/模块，共享进程与 DB，避免过早微服务化；二期按需拆分。

```
前端层（均为 React+Vite+shadcn）：用户 Portal | 管理 Admin | 开发者 Console(二期)
   │ 统一 REST/SSE + API Key/SSO 鉴权
   ▼
后端服务层 gateway-core（原编排网关：会话/编排/A2A/模型/会话存储）✅
   + 新增领域模块：AgentRegistryModule·TenantModule·IamModule·ModelAdminModule·CostAccountingModule·AuditModule·OpenApiModule（一期，同进程模块）
                  KnowledgeModule·RagModule·WorkflowModule·ModerationModule（二/三期）
共享基础设施：Nacos(A2A+配置) · Redis/DB · OTel→Admin
```

### 13.4 错误码规划（避免冲突）

按「千位段 = 大类」划分，段内按语义归类（不与具体编号硬绑定，新增码在所属段内递增即可）：

| 段 | 大类 | 段内语义归类（举例，非穷举） |
|---|---|---|
| `GW-1xxx` | 接入层 | 认证失败、授权拒绝（如 `GW-1003 无权限`）、限流、请求非法 |
| `GW-2xxx` | 编排与 A2A | 编排异常、A2A 调用（如 `GW-2003 Agent 超时`） |
| `GW-3xxx` | 模型 | 如 `GW-3001 模型不支持工具调用` |
| `GW-41xx` | Agent | 目录（`GW-4101/4102`）、生命周期（`GW-4113 紧急禁用`） |
| `GW-42xx` | 管理后台 | 租户/模型/Key/RBAC/配置 |
| `GW-43xx` | 成本中心 | |
| `GW-44xx` | 审计 | |
| `GW-45xx` | 开放 API | |

> 规约：千位段（1/2/3/41/42/43/44/45）决定大类，**不同模块不得跨段复用同一码**；段内编号在所属段内递增分配，新增时在本文档登记即可。`GW-1003`（无权限）与 `GW-4113`（紧急禁用）分属不同段，不冲突。

---

## 14. Agent 目录与市场

### 14.1 功能概述

Agent 目录是终端用户发现和使用 Agent 的统一入口。用户可浏览所有对自己可见的 Agent（受租户隔离与 RBAC 过滤，见 §4.3/§6.3），每个 Agent 展示核心元信息与使用统计。支持「一键开聊」——从目录跳转会话并自动携带 `@Agent` 前缀。目录是只读视图，底层来源于 Nacos A2A Registry 的 AgentCard 快照（§4.1）结合本地统计数据。

### 14.2 数据模型

```java
// gateway-domain/catalog/
public record AgentCatalogEntry(
    String name, String displayName, String description, List<String> skills,
    String provider, String version, AgentStatus status,
    long invocationCount, double rating, int favoriteCount,
    Instant lastPublishedAt, TenantId ownerTenant
) {}

public enum AgentStatus { DRAFT, PENDING_REVIEW, PUBLISHED, CANARY, DISABLED, DEPRECATED }

public record AgentCatalogQuery(
    Optional<String> keyword, Optional<String> skill,
    Optional<AgentStatus> status, Optional<String> provider,
    SortBy sortBy, boolean favoritesFirst
) { public enum SortBy { NAME, PUBLISHED_AT, INVOCATION_COUNT, RATING } }
```

### 14.3 核心接口

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| GET | `/v1/agents` | 列出目录（分页），自动应用租户+RBAC 过滤 | ✅ |
| GET | `/v1/agents/{name}` | 单个 Agent 详情 | ✅ |
| POST | `/v1/agents/{name}/favorite` | 收藏/取消收藏 | ✅ |
| GET | `/v1/agents/favorites` | 我收藏的 Agent | ✅ |
| GET | `/v1/agents/categories` | 能力类目列表 | ✅ |

错误码：`GW-4101 Agent 不存在或无权访问`、`GW-4102 目录查询参数非法`。

### 14.4 与其他模块交互

§4（AgentCard 来源 `AgentCardPort.snapshot()`）、§6（RbacFilter+TenantScopeFilter 决定可见性）、§5（一键开聊携带 `@AgentName`）、§7（invocationCount/rating 从指标聚合）。

### 14.5 一期范围 vs 二期

一期 ✅：列表/详情、搜索、收藏、一键开聊。二期 ◑：用户提交评分、树形类目、推荐。

---

## 15. Agent 生命周期管理

### 15.1 功能概述

管理员与 Agent 提供方对 Agent 进行治理的控制台，涵盖草稿→下线、注册→灰度发布的完整状态流转。与目录共享数据源，但操作权限严格隔离。

### 15.2 Agent 状态机

```
DRAFT ──(提交审核)──▶ PENDING_REVIEW ──(审核通过)──▶ PUBLISHED ◀──(灰度结束)── CANARY
 │                      │                  │                              │
 └──(拒绝，附原因)──────┘         (紧急禁用/计划下线)                  (回滚)
                                    │                                   │
                                    ▼                                   ▼
                                 DISABLED  ◀──(永久删除，仅管理员)───  DEPRECATED
```

| 状态 | 可见性 | 可被 LLM 调用 |
|---|---|---|
| DRAFT / PENDING_REVIEW / DISABLED | 否 | 否 |
| PUBLISHED | 是（按租户/RBAC） | 是 |
| CANARY | 受限 | 是（受限） |
| DEPRECATED | 是（标记「即将下线」） | 是（警告） |

### 15.3 注册与审核流

注册方式（一期 ✅）：**自动注册**（远程 Agent 向 Nacos A2A 注册，初始 `PENDING_REVIEW`）、**手动登记**（管理台填写 AgentCard）。审核检查点：AgentCard 必填完整性、description 质量、schema 合法性、提供方归属。

### 15.4 版本与灰度发布

一期 ✅：多版本并存（§4.4，默认最新可用版本）、版本选择（`@AgentName:v1.2.0`）、回滚。二期 ◑：CANARY 灰度（按租户/比例路由）。

### 15.5 上下线与紧急禁用

紧急禁用：管理台操作 → 写 Nacos Blocklist → ToolRegistry 监听 → 立即从工具集移除（呼应 §4.3），错误码 `GW-4113 Agent 已紧急禁用`（生命周期子段，见 §13.4）。

### 15.6 管理接口与 RBAC 角色

| 方法 | 路径 | 用途 | 角色 |
|---|---|---|---|
| POST | `/v1/admin/agents` | 创建（草稿） | AGENT_ADMIN / AGENT_PROVIDER |
| PUT | `/v1/admin/agents/{name}` | 更新元信息 | AGENT_ADMIN / AGENT_PROVIDER（仅自己） |
| POST | `/v1/admin/agents/{name}/submit` | 提交审核 | AGENT_PROVIDER |
| POST | `/v1/admin/agents/{name}/approve\|reject` | 审核 | AGENT_ADMIN |
| POST | `/v1/admin/agents/{name}/publish\|canary\|disable\|rollback` | 发布/灰度/禁用/回滚 | AGENT_ADMIN |

RBAC 角色（扩展 §6.3）：`AGENT_ADMIN`（全局治理）、`AGENT_PROVIDER`（仅自己提供方）、`AGENT_VIEWER`（只读）。

### 15.7 数据模型

```java
// gateway-domain/lifecycle/
public record AgentLifecycle(
    String name, AgentVersion currentVersion, AgentStatus status,
    Optional<String> canaryConfig, List<AgentVersion> versions,
    Instant statusChangedAt, UserId changedBy, String rejectReason
) {}
public record AgentVersion(String version, JsonNode agentCard,
                           Instant publishedAt, boolean isCanary,
                           Optional<CanaryStrategy> canaryStrategy) {}
public record CanaryStrategy(StrategyType type, int percentage,
                             List<TenantId> tenantWhitelist) { enum StrategyType { PERCENTAGE, TENANT_LIST } }
```

### 15.8 一期范围 vs 二期

一期 ✅：状态机、自动注册、审核流、多版本、回滚、紧急禁用、提供方自助。二期 ◑：灰度发布、跨租户复用、多级审批、自动质量评分。

---

## 16. 租户与组织管理

### 16.1 功能概述

多租户体系的维护，与 §6.2 衔接。租户（部门/团队）CRUD、组织树、成员归属、租户级配额（映射 §8.3 限流维度）。

### 16.2 数据模型

```java
// gateway-domain/iam/
public record Tenant(TenantId id, String name, TenantId parentId,  // parentId 空=根租户
                     Quota quota, Instant createdAt, boolean enabled) {}
public record Quota(int qpsLimit, long dailyTokenBudget,
                    Map<ModelId, Integer> modelSpecificLimits) {}
public record Member(UserId user, TenantId tenant, Role role, Instant joinedAt) {}
```

### 16.3 接口

`POST/GET/PUT/DELETE /v1/admin/tenants`、`GET/POST/DELETE /v1/admin/tenants/{id}/members`。错误码：`GW-4201`。

### 16.4 配置下发链路

租户配额变更 → Nacos `gateway.rate-limits.tenant.{id}` → RateLimiter 热更新。组织树变更 → TenantScopeFilter 按层级过滤 Agent 可见性。

### 16.5 一期范围

✅ 租户 CRUD、两级组织树、租户级配额、成员管理、配额热更新。◑ 二期：多级树、租户迁移合并。

---

## 17. 模型管理（可视化）

### 17.1 功能概述

对 §5.5.2 `gateway.models` yaml 的可视化 CRUD：模型注册/上下线、密钥管理（不落明文）、能力标签、成本单价、enabled、tenantScope。变更热下发到 Nacos，网关 ModelRegistry 热更新。提供连通性测试。

### 17.2 数据模型

```java
// gateway-domain/model/  （ModelDef 的权威定义，§3.3 概览以此为准）
public record ModelDef(ModelId id, String provider, String displayName, String endpoint,
                       String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                       BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                       boolean enabled, List<String> tenantScope) {}
public enum Capability { FUNCTION_CALLING, VISION }
public record ConnectivityTest(ModelId id, boolean success, String error, Instant testedAt) {}
// Capability 枚举唯一在此定义，§3.3 不重复。
```

### 17.3 接口

`GET/POST/PUT/DELETE /v1/admin/models`、`POST /v1/admin/models/{id}/test`、`GET /v1/admin/models/providers`。错误码：`GW-4202`。

### 17.4 配置下发链路

管理员操作 → 写 Nacos `gateway.models` → `@RefreshScope` → ChatClientFactory 失效对应缓存。密钥经 `${SECRET:XXX}` 占位符引用，明文存外部密钥管理（Vault）。

### 17.5 一期范围

✅ 模型 CRUD、能力标签、tenantScope、连通性测试、热更新、fallbackToolModel 配置、成本单价。◑ 二期：模型灰度。

---

## 18. API Key 管理

### 18.1 功能概述

§6.1 API Key 通道的完整生命周期：签发/吊销/轮换，Key 作用域绑定租户、模型集合、有效期、QPS 限额。

### 18.2 数据模型

```java
// gateway-domain/iam/
public record ApiKeyScope(TenantId tenant, Set<ModelId> allowedModels,
                          Instant expiresAt, int qpsLimit) {}
public record ApiKey(ApiKeyId id, UserId createdBy, ApiKeyScope scope,
                     Instant lastUsedAt, boolean revoked) {}
public record ApiKeyStats(ApiKeyId id, long totalCalls, Instant lastUsedAt) {}
```

### 18.3 接口

`POST/GET/PUT/DELETE /v1/admin/api-keys`、`POST /v1/admin/api-keys/{id}/rotate`、`GET /v1/admin/api-keys/{id}/stats`。错误码：`GW-4203`。签发时完整 key 仅显示一次。

### 18.4 与网关交互

请求 `X-API-Key` → AuthWebFilter → 查 Redis/DB 解析 scope → 构造 AuthPrincipal。吊销 → revoked=true 推 Redis 黑名单 → 拦截层优先查黑名单。

### 18.5 一期范围

✅ 签发/吊销、作用域绑定、有效期、QPS 限额、最近使用统计。◑ 二期：轮换过渡期、Key 级 token 预算。

---

## 19. RBAC 权限管理（可视化）

### 19.1 功能概述

§6.3 Agent 级 + 模型级 RBAC 的可视化配置。角色定义、用户/组→角色→权限映射、策略预览（dry-run 某用户实际可用 Agent/模型）。

### 19.2 数据模型

```java
// gateway-domain/iam/
public record Role(RoleId id, String name, String description, Set<Permission> permissions) {}
public sealed interface Permission permits AgentPermission, ModelPermission, SkillPermission {}
public record AgentPermission(String agentName, Set<String> allowedSkills) {}
public record ModelPermission(Set<ModelId> models) {}
public record SkillPermission(String agentName, String skillName) {}
public record PolicyPreview(UserId user, TenantId tenant,
                            Set<String> allowedAgents, Set<ModelId> allowedModels) {}
```

### 19.3 接口

`GET/POST/PUT/DELETE /v1/admin/roles`、`GET/POST/DELETE /v1/admin/users/{id}/roles`、`POST /v1/admin/rbac/preview`。错误码：`GW-4204`。

### 19.4 授权决策链路

用户请求 → AuthPrincipal → 查角色汇总 Permissions → RbacFilter 注入时过滤 → A2A 调用前二次校验（纵深防御）。策略变更 → Nacos `gateway.rbac.*` → 热更新。

### 19.5 一期范围

✅ 角色管理、用户角色映射、Agent 级 + 模型级权限、策略预览。◑ 二期：组管理、Skill 级、数据级权限。

---

## 20. 配置中心（可视化）

### 20.1 功能概述

把散落 Nacos 的配置统一可视化：路由策略（§4.3 PinRule/Blocklist）、限流阈值（§8.3）、fallbackToolModel（§5.5.5）、特性开关。配置版本历史、回滚、变更审计。变更事件上 trace（§7）。

### 20.2 数据模型

```java
// gateway-domain/config/
public record ConfigVersion(ConfigId id, int version, String content,
                            String changedBy, Instant changedAt, String changeReason) {}
public record RoutingConfig(List<PinRule> pinRules, List<Blocklist> blocklist) {}
public record PinRule(String intent, String keyword, String targetAgent) {}
public record Blocklist(Set<String> agentNames) {}
public record FeatureToggle(String feature, boolean enabled) {}
```

### 20.3 接口

`GET/PUT /v1/admin/config/{routing|rate-limits|fallback-model|features}`、`GET /v1/admin/config/versions`、`POST /v1/admin/config/rollback`、`GET /v1/admin/config/audit-log`。错误码：`GW-4205`。

### 20.4 配置下发链路

管理后台变更 → 写 Nacos Data ID → `@RefreshScope` 监听 → 对应组件热更新 → 变更事件上 OTel trace。

### 20.5 一期范围

✅ 路由/限流/fallback/特性开关可视化、热更新、版本历史、回滚、变更审计。◑ 二期：配置 diff。

---

## 21. 成本中心与计费

### 21.1 功能概述

统一用量与成本核算，支撑内部 chargeback 与预算管理。核心原则**单一数据源**——所有 token 用量由 `llm.tokens{in,out}`（§7.2）统一产出，同时喂养限流计数器（§8.3）和成本核算表。成本 = token × 模型单价（§5.5.2）。

### 21.2 成本数据模型

```java
// gateway-domain/billing/
public record UsageRecord(String recordId, TenantId tenant, UserId user, ModelId model,
                          String agentName, Instant timestamp,
                          long tokensIn, long tokensOut, BigDecimal cost) {}
public record CostRecord(String id, TenantId tenant, UserId user, ModelId model,
                         String agentName, LocalDate date,
                         long totalTokensIn, long totalTokensOut, BigDecimal totalCost) {}
public record Budget(TenantId tenant, UserId user, BudgetType type,
                     BigDecimal dailyLimit, BigDecimal monthlyLimit,
                     BigDecimal currentDailyUsed, BigDecimal currentMonthlyUsed,
                     AlertThreshold alertThreshold, boolean alertSent) { enum BudgetType { TOKEN, MONEY } }
```

### 21.3 成本数据流

```
每次 LLM 调用结束 → ObservabilityHooks 记录 llm.tokens{in,out}（单一数据源）
                              │ 异步 MQ
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
  限流计数器(§8.3)       成本写入器→OLAP        OTel 上报(§7.2)
  Redis 累计             usage_record/cost_record   Prometheus
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
          预算校验        报表查询       告警触发
```

单一数据源原则：同一条用量经 MQ 分发——一份喂 Redis 限流、一份喂 OLAP 成本表。限流阈值用 token 数、成本阈值用金额（单价换算），两端同源一致。

### 21.4 预算与告警

| 预算类型 | 单位 | 校验点 | 超限处理 |
|---|---|---|---|
| Token 预算 | token | 限流器（§8.3） | 拒绝新请求（429） |
| 金额预算 | 元 | 成本写入异步校验 | 告警 + 记录，**不阻断** |

告警：UsageWriter 落库累加 currentUsed → 超 alertThreshold.percent 且未发过 → 发告警（邮件/Webhook）。

### 21.5 Chargeback 报表

✅ 一期：租户成本（tenant×model×date）、用户成本、Agent 成本、趋势。◑ 二期：部门分摊。导出 CSV/Excel。

### 21.6 接口

`GET /v1/admin/billing/costs`、`GET /v1/admin/billing/usage/export`、`GET/POST/PUT /v1/admin/billing/budgets`。错误码：`GW-4301~4303`。

### 21.7 一期范围

✅ token 采集（单一数据源）、成本核算、租户级 Token/金额预算、报表导出、实时成本看板、静态阈值告警。◑ 二期：用户级预算、审批、部门分摊、智能异常检测。

---

## 22. 审计日志

### 22.1 功能概述

合规追溯能力，记录 who→what→when→result。原则 **append-only + 不可篡改**（§8.6）。与 §7 trace 明确边界：trace 是性能/调用链（运维用），审计是合规追溯（安全用）。

### 22.2 审计事件分类

| 类别 | 事件 | 一期 |
|---|---|---|
| 认证 | LOGIN / API_KEY_CREATE / API_KEY_DELETE / AUTH_FAILED | ✅ |
| 授权 | GRANT_CREATE/UPDATE/DELETE、RBAC_DENIED | ✅ |
| 配置 | AGENT_REGISTER/UPDATE/DEREGISTER、MODEL_CONFIG_UPDATE | ✅ |
| 会话 | SESSION_CHAT（who→which Agent/model→when→result） | ✅ |
| 敏感 | RATE_LIMIT_EXCEEDED | ✅ |
| 其他 | LOGOUT / SESSION_CREATE/CLOSE | ◑ 二期 |

### 22.3 审计日志模型

```java
// gateway-domain/audit/
public record AuditLog(String eventId, TenantId tenant, UserId actor, ActorType actorType,
                       AuditEventType eventType, Instant timestamp,
                       String resourceType, String resourceId, String action,
                       AuditResult result, String errorMessage, JsonNode details,
                       String ipAddress, String userAgent) {}
public enum ActorType { HUMAN, SERVICE, SYSTEM }
public enum AuditResult { SUCCESS, FAILURE }
```

### 22.4 不可篡改与保留策略

存储层 append-only（禁 UPDATE/DELETE，仅按 TTL 清理）；应用层 AuditWriter 仅 `append()`。保留：热 30 天（ES/DB）→ 温 31-180 天（对象存储）→ 冷归档/删除（可配，符合等保）。

### 22.5 审计 vs Trace 边界

| 维度 | 审计（§22） | Trace（§7） |
|---|---|---|
| 目的 | 合规追溯 | 性能/调用链 |
| 内容 | who→what→when→result | span 树、延迟 |
| 保留 | 长期（180 天+） | 短期（7-30 天） |
| 不可篡改 | 必须 | 不要求 |

审计不记技术细节（headers/span），trace 不记业务身份——两者互补不重复。

### 22.6 接口

`GET /v1/admin/audit/logs`、`GET /v1/admin/audit/logs/export`、`GET /v1/admin/audit/stats`。错误码：`GW-4401~4403`。

### 22.7 一期范围

✅ 事件采集、append-only 存储、查询筛选、CSV 导出、可配 TTL、敏感操作记录、基础统计。◑ 二期：WORM/数字签名、全文检索、PDF 合规报告、**告警通道**（邮件/IM/Webhook，依赖 §25 Webhook 二期落地——一期仅记录到审计日志，不实时推送）。

---

## 23. 开放 API

### 23.1 功能概述

网关作为统一入口对外提供程序化 REST/SSE API，供公司内业务系统/脚本集成。复用 gateway-core 编排能力，不另起一套。原则：单一入口、版本化、契约优先。

### 23.2 端点清单

| 路径 | 方法 | 功能 | 流式 | 一期 |
|---|---|---|---|---|
| `/v1/chat` | POST | 发消息（非流式） | 否 | ✅ |
| `/v1/chat/stream` | POST | 发消息（流式 SSE） | 是 | ✅ |
| `/v1/sessions` | POST | 创建会话 | 否 | ✅ |
| `/v1/sessions/{id}` | GET | 会话详情 | 否 | ✅ |
| `/v1/sessions/{id}/messages` | GET | 历史分页 | 否 | ✅ |
| `/v1/agents` | GET | Agent 目录（按授权过滤） | 否 | ✅ |
| `/v1/models` | GET | 模型列表（按授权过滤） | 否 | ✅ |
| `/v1/health` | GET | 健康检查 | 否 | ✅ |

### 23.3 鉴权与限流

统一走 **API Key 通道**（§6.1）。请求 `X-API-Key` → AuthWebFilter 校验 → 解析作用域 → AuthPrincipal（含 agentGrants/allowedModels）。超出作用域返回 `GW-1003 无权限`（接入层授权拒绝码，与 §8.1 认证错误同段）。限流走 **API Key 级**（§8.3）。

### 23.4 OpenAPI 规范与类型生成

启动时导出 OpenAPI 3.0 到 `/v1/openapi.json`；前端类型经 `openapi-typescript` 生成（呼应 §12.5）。◑ 二期：Java/Python/Go SDK。

### 23.5 一期范围 vs 二期

一期 ✅：chat 端点（流式+非流式）、会话管理、Agent/Model 目录查询、API Key 管理、OpenAPI 导出。◑ 二期：Webhook 回调、批量导入导出、多语言 SDK。

---

## 24. 多渠道 IM 接入（二期）

### 24.1 功能概述

二期把网关嵌入员工日常 IM（飞书/钉钉/企微）。@机器人发消息 → 网关编排 → 回复到 IM。核心挑战：IM 异构协议 ↔ 网关统一会话模型的映射、流式能力差异。

### 24.2 渠道适配层架构

```
飞书/钉钉/企微 ──(Webhook 回调)──▶ gateway-interfaces-im
  FeishuAdapter / DingAdapter / WeComAdapter
              ▼
  ImChannelGateway（统一抽象）
    ① 身份映射：IM user → AuthPrincipal（接公司 IDP/SSO）
    ② 消息映射：IM msg → ChatRequest
    ③ 会话映射：IM conversation → Session（IM 线程 ID 作元数据）
    ④ 回复映射：ChatResponse → IM msg
              ▼ 复用 gateway-core 编排
         Orchestrator（与 Web UI 共享）
```

### 24.3 IM ↔ 网关映射

用户身份经 IDP 映射；租户从 IDP claim；会话 1:1 绑定（IM 线程 ID 作 session 元数据）；图片转 base64/URL 供模型识别。

### 24.4 流式能力差异

| IM | 流式支持 | 策略 |
|---|---|---|
| 飞书 | 卡片流式更新 | ✅ 透传 chunk 逐块更新卡片 |
| 钉钉/企微 | 多为完整回复 | ✅ 先发「正在思考…」，完整后替换 |

网关编排仍走流式 SSE（§8.2），适配层按平台能力缓存或透传。

### 24.5 二期范围

✅ 三家 Adapter、身份映射、会话映射、流式适配、群聊 @ 解析。◑：卡片按钮交互、多租户切换。

---

## 25. 出站 Webhook 与事件（二期）

### 25.1 功能概述

网关事件（会话开始/结束、Agent 调用、成本阈值触发、Agent 上下线）推送给外部系统。订阅-投递机制，异步解耦。与 §7 trace/§22 审计边界：事件是「对外通知」，trace/审计是「内部记录」。

### 25.2 事件分类

| 事件 | 触发 | 二期 |
|---|---|---|
| `session.started` / `session.ended` | 会话创建/结束 | ✅ |
| `agent.invoked` / `agent.available_changed` | Agent 调用完成/上下线 | ✅ |
| `cost.threshold_exceeded` | 成本超阈值 | ✅ |
| `agent.timeout` | Agent 调用超时 | ✅ |
| `llm.error_spiked` | LLM 错误率异常 | ◑ |

### 25.3 订阅/投递/重试/签名

订阅配置（url/events[]/secret/active）存 Nacos/DB。投递：HTTP POST + HMAC-SHA256 签名（`X-Gateway-Signature`）。重试：指数退避（1s,2s,4s,8s,… 最多 5 次），失败入死信队列。

### 25.4 与 Trace/审计的边界

事件（对外通知、近实时、不可变日志）≠ trace（调用链、OTel 短周期）≠ 审计（合规、长周期 append-only）。三者独立不替代。

### 25.5 二期范围

✅ 核心事件、订阅管理、投递重试死信、签名。◑：批量投递、事件过滤。

---

## 26. 开发者控制台与 Playground（二期）

### 26.1 开发者控制台

Agent 提供方入口（只读视图）：Agent 管理（AgentCard 列表，对接 §4）、接入指南、API 文档、SDK 下载、调用统计（来自 §7）。

### 26.2 Agent Playground（与普通会话的区别）

| 维度 | 普通会话（§2） | Playground |
|---|---|---|
| 目标用户 | 终端用户 | Agent 开发者 |
| 目的 | 获得答案 | 调试 A2A 协议交互 |
| 可见性 | 隐藏协议细节 | **显示原始 A2A JSON-RPC payload** |
| 工具选择 | LLM 自主决策（§2.2） | **强制指定某 Agent，绕过 LLM** |
| 校验 | 自然语言评价 | **JSON Schema 校验**（对照 outputSchema） |

流程：选 Agent → 输入符合 inputSchema 的 payload → 展示请求/A2A 帧/返回/Schema 校验，支持流式测试。

### 26.3 AgentCard 编辑器

可视化编辑 AgentCard（name/description/skills/input-outputSchema），生成符合 A2A 规范的 JSON。亮点：**描述质量自检**——内置小模型评估 description 对 LLM 的可理解性并给改进建议。

### 26.4 二期范围

✅ 开发者控制台、Playground（单 Agent 流式测试）、AgentCard 编辑器 + 描述自检、Java/Python SDK。◑：多 Agent 协作调试（三期工作流覆盖）。

---

## 27. 知识库与 RAG（三期）

### 27.1 功能概述

知识库作为「资源」层，两类场景：① Agent 引用（AgentCard 声明依赖，调用时自动注入检索）；② 会话直接 RAG（用户引用知识库，编排前检索增强）。价值：企业文档（PDF/Word/MD）转为结构化知识，提升准确性与溯源。

### 27.2 架构

```
上传文档 → 文档解析(Parser) → 切片(Chunker) → Embedding(向量化)
                                           │
                            ┌──────────────┴──────────────┐
                            ▼                             ▼
                       向量库(Milvus/PGVector)        元数据(关系DB)
检索：会话请求 → 意图识别 → 向量检索(ANN) → 重排序(Rerank) → 注入 LLM 上下文 → 生成回答(带引用)
```

### 27.3 与 Agent/会话的关系

| 关系 | 说明 |
|---|---|
| Agent 绑定 | AgentCard 增 `knowledgeBases` 字段，调用前检索拼入 args |
| 会话 RAG | 用户指定知识库，编排前检索作 System Message 注入 |
| 知识库作工具 | 知识库注册为 `@Tool("search_kb")`，LLM 主动调用 |

引用溯源：检索结果带 `{docId,chunkId,sourceUrl,snippet}`，回答标注 `[来源：文档X]`。

### 27.4 数据模型草案

```java
// gateway-domain/knowledge/
public record KnowledgeBase(KnowledgeBaseId id, TenantId tenant, String name,
                            EmbeddingModel embeddingModel, boolean enabled) {}
public record Document(String id, String originalName, String mimeType,
                       ProcessStatus status) { enum ProcessStatus { PENDING,PARSING,CHUNKING,EMBEDDING,READY,FAILED } }
public record Chunk(String id, String docId, int index, String content, float[] vector) {}
public record RetrievalResult(ChunkId chunk, String content, float score, SourceRef source) {}
```

### 27.5 三期范围

✅ PDF/Word/MD 解析、固定切片+滑动窗口、Qwen-Embedding、Milvus/PGVector、ANN 检索、引用溯源。◑：多模态、知识图谱、混合检索。

---

## 28. 内容审核（三期）

### 28.1 功能概述

网关安全门禁，贯穿输入输出：

```
用户输入 → 输入审核(拦截/告警) → 编排 → LLM/Agent → 输出审核(拦截/脱敏/告警) → 用户
```

目标：输入防恶意 prompt（提示注入/越权/敏感词）、保护 PII；输出防敏感泄露、毒性。与 §8.6 安全、§22 审计衔接。

### 28.2 输入输出审核策略

| 维度 | 输入 | 输出 |
|---|---|---|
| 敏感词 | 政治/暴力/色情 | 同左 |
| PII | 手机/邮箱/身份证（脱敏/告警） | 防泄露 |
| 提示注入/越权 | 检测「忽略之前指令」「绕过权限」 | N/A |
| 毒性 | N/A | 仇恨/歧视/辱骂 |

```java
public record ModerationRule(String id, TenantId tenant, String agent,
                             RuleType type, Action action, JsonNode pattern, float threshold) {}
public enum RuleType { KEYWORD, PII, PROMPT_INJECTION, TOXICITY, SENSITIVE_INFO }
public enum Action { BLOCK, MASK, ALERT, PASS }
```

规则存 Nacos，按租户/Agent 覆盖，热更新。

### 28.3 命中处置

敏感词/提示注入高置信 → BLOCK；PII 低风险 → MASK 静默；PII 高风险 → ALERT + 审计。命中事件写 `moderation_audit`，关联 §22。

### 28.4 与安全/审计衔接

审核是「内容维度」安全（补充 mTLS 的「传输维度」）；指标 `moderation.blocks/alerts` 上 OTel，告警 P1；命中作审计 `risk_event` 字段。

### 28.5 三期范围

✅ 关键词库、基础 PII 正则、提示注入模式匹配、基础毒性（LLM 辅助）、规则热更新、审核日志。◑：NER 高级 PII、多模态审核。

---

## 29. 工作流可视化编排（三期）

### 29.1 功能概述（与 LLM 自主编排 §2.2 的区别）

| 维度 | LLM 自主编排（§2.2） | 工作流编排 |
|---|---|---|
| 驱动者 | LLM 自主决策 | 人工预定义固定流程 |
| 确定性 | 动态 | 静态、可重复 |
| 适用 | 开放式对话、探索性 | 标准化业务流程、合规要求高 |
| 例子 | 「查下天气」 | 「报销审批：填单→主管→财务→打款」 |

工作流可作为特殊「Agent」注册到目录，或作为会话的一种执行模式。

### 29.2 工作流模型（DAG）

```java
public record Workflow(WorkflowId id, TenantId tenant, String name,
                      WorkflowDAG dag, TriggerType triggerType, JsonNode triggerConfig) {}
public record WorkflowDAG(List<Node> nodes, List<Edge> edges) { void validate(); }
public record Node(String id, NodeType type, JsonNode config) {}
public enum NodeType { AGENT_CALL, CONDITION, LOOP, PARALLEL, MANUAL_APPROVAL, DELAY, RAG_RETRIEVAL, TEMPLATE }
public record Edge(String from, String to, JsonNode condition) {}
public enum TriggerType { MANUAL, API, SCHEDULE, EVENT }
```

节点：AGENT_CALL（调 Agent，参数映射）、CONDITION（表达式分支）、LOOP（遍历）、PARALLEL（并行合并）、MANUAL_APPROVAL（审批回调+超时降级）、RAG_RETRIEVAL（注入检索）。

### 29.3 触发与执行

触发：MANUAL（会话选「启动工作流 X」）、API（`POST /v1/workflows/{id}/trigger`）、SCHEDULE（Cron）、EVENT。执行引擎：DAG 解析→节点执行器→状态机→上下文传递。流式支持：中间节点结果实时推送（SSE）。

### 29.4 与 Agent 目录的关系

工作流发布生成 AgentCard（`type=WORKFLOW`）注册到 Nacos，LLM 可作普通工具调用——此时 LLM 自主编排与固定工作流融合。区别：入口为 WorkflowExecutor（执行本地 DAG）而非 A2AClient。

### 29.5 三期范围

✅ 可视化编辑器（React Flow）、节点类型（AGENT_CALL/CONDITION/LOOP/PARALLEL/MANUAL_APPROVAL）、MANUAL/API 触发、执行引擎（状态持久化 Redis）、发布为虚拟 Agent。◑：子工作流、动态节点、分布式执行。

---

## 附录 A：术语表

- **A2A**：Agent-to-Agent 协议（Google 发起，Linux Foundation 托管），JSON-RPC over HTTP+SSE。
- **AgentCard**：A2A 中描述 Agent 能力的元数据（name/description/skills/schemas/version）。
- **Nacos A2A Registry**：Nacos 3.1+ 内置的 Agent 注册发现中心。
- **SAA**：Spring AI Alibaba。
- **ChatClient/ChatModel**：Spring AI 的大模型统一抽象。
- **Fan-out/Join**：一轮内并行多 Agent 调用、结果汇总。
- **ContextWindow**：注入 LLM 前的历史裁剪策略。
- **Agent 目录/生命周期**：§14/§15，Agent 的发现浏览与状态治理（草稿→审核→发布→灰度→下线）。
- **Chargeback**：内部成本分摊，按租户/用户/模型将 AI 成本计回各业务方。
- **Playground**：面向 Agent 开发者的调试台，显示原始 A2A 协议帧、强制指定 Agent、Schema 校验。
- **RAG**：检索增强生成，上传文档向量化后注入 LLM 上下文。
- **工作流编排**：人工预定义的固定 DAG 流程，区别于 LLM 自主编排。
- **Modular Monolith**：模块化单体，领域服务作为同进程模块共享 DB，二期再按需拆微服务。

## 附录 B：参考来源

- Spring AI Alibaba GitHub（2.0.0-M1 升级 Spring AI 2.0.0-M1 + Spring Boot 4.0.0）：https://github.com/alibaba/spring-ai-alibaba/releases
- Nacos A2A Agent Registry：https://nacos.io/en/docs/next/manual/user/ai/agent-registry/
- Spring AI Alibaba + Nacos 分布式 Multi-Agent 指南：https://java2ai.com/docs/overview
- A2A 协议（AgentScope Java）：https://java.agentscope.io/v1/en/docs/task/a2a.html

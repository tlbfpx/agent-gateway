# Proposal: A2A 协议客户端 + Nacos 发现（add-a2a-and-discovery）

> **状态：✅ 已完成**（2026-08-13）。验收见末尾「实现结果」。

## 变更概述

本 change 实现网关作为「智能编排器」的核心能力——通过 A2A 协议调用远程 Agent，并通过 Nacos A2A Registry 动态发现 AgentCard。具体交付 `gateway-infra-a2a`（A2A 协议客户端，实现 `ToolPort`）与 `gateway-infra-nacos`（Nacos 发现 + AgentCard 缓存，实现 `AgentCardPort`）。

## 动机

1. **「通用网关」的核心价值**：网关不实现业务 Agent，只编排——编排的前提是能发现并调用远程 Agent。本 change 落地这一核心能力。
2. **实现 domain 出站端口**：`ToolPort` 与 `AgentCardPort` 在 `add-foundation-skeleton` 中已定义（JDK Flow，零框架），本 change 是其首次 infra 实现，验证「domain 零框架 + infra 拉框架」的架构约束。
3. **Nacos A2A Registry 兼容性验证**：Foundation Spike 只验证了 LLM starter，Nacos A2A 在 Spring Boot 4.0 下的兼容性需独立 Spike 后再实现。

不做的后果：编排核心无法真正调用远程 Agent，端到端验证无法闭环；Nacos A2A 兼容性问题延迟发现导致返工。

## 范围

### 做什么
| 任务 | 交付物 |
|------|--------|
| **Spike** | Nacos 3.x A2A Registry 在 Boot 4.0 下兼容性验证（nacos-client 版本、API、testcontainers） |
| **gateway-infra-nacos** | `AgentCardPort` 实现：推送优先（Nacos Listener）+ 定时拉取兜底（60s）+ Caffeine 缓存（30s TTL） |
| **gateway-infra-a2a** | `ToolPort` 实现：A2A JSON-RPC over HTTP+SSE 客户端，含 SSE→Flow 适配器、超时/重试/降级 |
| **错误处理** | A2A 错误映射到 `ToolEvent.Error`；Nacos 不可达用本地缓存兜底 |
| **测试** | WireMock 模拟远程 Agent、testcontainers Nacos，覆盖率 ≥80% |

### 不做（YAGNI）
- **ToolRegistry 路由消费**：`AgentCardPort.watch()` 能发布快照，但谁消费、如何适配为 `@Tool`、热更新注入，留待「编排核心」change。
- **application 层编排逻辑**：`ChatOrchestrator` 如何调 `ToolPort`，留待编排 change。
- **REST 接口**：infra 不暴露 HTTP 端点（由 gateway-interfaces change 统一暴露）。

## 依赖与风险
| 依赖 | 版本 | 风险 |
|------|------|------|
| nacos-client | 待 Spike | A2A Registry API 可能变更 |
| HTTP/SSE 客户端 | WebClient/WebFlux 或 java.net.http | JDK 21+ SSE 支持需验证 |
| Caffeine | 3.x | 无特殊风险 |

缓解：Spike 前置门；适配层隔离（domain 端口不感知 A2A/Nacos）；WireMock + testcontainers 覆盖边界。

## 前置 Spike
验证：① nacos-client GAV 与 Boot 4.0 依赖冲突（Netty 版本）；② A2A Registry API 可用性；③ testcontainers-nacos 是否支持 Nacos 3.x；④ SAA 是否提供 A2A 客户端封装（无则自研 JSON-RPC over SSE）。
产出：`docs/superpowers/spike/2026-08-13-nacos-a2a-compat-report.md`。

## 验收标准
1. `gateway-infra-nacos` 实现 `AgentCardPort`：`snapshot()` 返回缓存快照，`watch()` 发布变更（Flow.Publisher）。
2. `gateway-infra-a2a` 实现 `ToolPort`：`invoke()` 返回 `Flow.Publisher<ToolEvent>`，正确解析 A2A SSE 为 Delta/Complete/Error。
3. Nacos 不可达时本地缓存兜底（返回上次快照，不抛异常）。
4. A2A 超时/重试/降级正确，错误映射到 `ToolEvent.Error`。
5. 测试覆盖率 ≥80%（JaCoCo）。
6. `mvn clean test` 全绿。

## 实现结果（2026-08-13 完成）

| 验收项 | 结果 |
|---|---|
| AgentCardPort（snapshot + watch Flow） | ✅ NacosAgentCardPort（复用 Nacos 内置 AiService：subscribeAgentCard 推送 + getAgentCard 拉取），11 测试 |
| ToolPort（invoke → Flow.Publisher<ToolEvent>） | ✅ A2aToolPort（WebClient SSE → SseEventMapper → 标准库 FlowAdapters），4 WireMock 端到端 + 7 mapper 测试 |
| Nacos 不可达降级 | ✅ 保留上次缓存不抛异常（getByName 降级返回缓存/订阅失败降级回调） |
| A2A 超时/错误映射 | ✅ timeout→ToolEvent.Error(A2A_TIMEOUT)；连接/4xx/5xx→Error(A2A_ERROR)；endpointUrl 缺失→Error(A2A_NO_ENDPOINT) 不请求 |
| 覆盖率 ≥80% | ✅ infra-nacos / infra-a2a 各 JaCoCo check（业务逻辑，config 排除） |
| mvn clean test 全绿 | ✅ domain 57 + nacos 11 + a2a 11 + llm 54 + bootstrap 1 = 134 测试，BUILD SUCCESS |

**测试**：22 个新测试（11 nacos + 11 a2a，含 4 个 WireMock 真 SSE 端到端），domain 未改。

**实现期关键决策**：
1. **Nacos A2A API javap 核对**（主线先做，最可靠）：`AiFactory.createAiService(Properties)`、`AiService.subscribeAgentCard(name, listener)`、`getAgentCard(name)`、`AbstractNacosAgentCardListener.onEvent(NacosAgentCardEvent)`、`AgentCardDetailInfo`（extends AgentCard extends AgentCardBasicInfo：getName/Description/Version + getSkills→List<AgentSkill>.getName + getUrl）。
2. **Nacos A2A 无 listAll API**：snapshot() 只含已订阅/拉取的 Agent。初始订阅名集合由 `gateway.a2a.initial-agents` 配置注入。真正"发现未知 Agent"留编排层配合（Nacos 服务列表 + 逐个 getAgentCard）。
3. **SSE→Flow 适配器用标准库** `org.reactivestreams.FlowAdapters.toFlowPublisher`（与 multi-model 一致，背压/cancel 契约透传，弃手写 SubmissionPublisher）。
4. **条件装配**：InfraNacosAutoConfiguration `@ConditionalOnProperty(nacos.addr)`，无 nacos.addr 空启动。
5. **Nacos A2A 模型无 JSON schema**（用 skills + url 表达能力）：domain AgentCard 的 inputSchema/outputSchema 填空 "{}"。
6. **bootstrap 接线** 全 4 个 infra（llm/nacos/a2a），条件装配空启动 contextLoads 通过。

## 关联文档
- 项目级设计：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§2 数据流、§3.3 domain 端口、§4 Agent 注册发现）
- 前置 change：`openspec/changes/add-foundation-skeleton/`（domain 端口定义）
- 本 change design：`openspec/changes/add-a2a-and-discovery/design.md`
- 本 change tasks：`openspec/changes/add-a2a-and-discovery/tasks.md`

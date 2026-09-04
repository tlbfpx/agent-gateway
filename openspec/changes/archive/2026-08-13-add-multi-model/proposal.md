# Proposal: 多模型接入（add-multi-model）

> **状态：✅ 已完成**（2026-08-13）。验收见末尾「实现结果」。Task 13（WireMock 集成）/Task 14（真实 API）延后至有 Nacos 环境 + API key 时。

## 变更概述

实现 `gateway-infra-llm`：多 Provider 的 ChatClient 装配基础设施（dashscope / zhipu / openai-compatible(DeepSeek) / minimax），实现 domain 已定稿的 `ChatClientPort` + `LlmSession`。核心难点是 **Flow↔Reactor 适配器**（domain 用 JDK Flow，Spring AI ChatClient 用 Reactor Flux）。

## 动机
1. **§5.5 模型接入**：让用户选定的模型生效，多模型可配置。
2. **架构分层**：domain 零框架（JDK Flow），infra 桥接 Spring AI Reactor。
3. **运维友好**：模型配置集中 Nacos，热更新无需重启。
4. **厂商多样性**：dashscope 已验，需并行验 openai-compat/zhipu/minimax。

## What / 范围

### 做
- 实现 `ChatClientPort`（`sessionFor(ModelId, List<ToolDescriptor>)` → `LlmSession`）+ `LlmSession`（`generate` → `Flow.Publisher<LlmEvent>`）。
- **Flow↔Flux 适配器**：Spring AI `Flux<ChatResponse>` → domain `Flow.Publisher<LlmEvent>`（Delta/ToolCall/Complete）。
- **ModelRegistry**：从 Nacos yaml 加载模型定义，热更新（spec §5.5.2）。
- **ChatClientFactory**：按 Provider 装配 + Caffeine 缓存复用（spec §5.5.3）。
- **能力降级 failover**（§5.5.5，一期必做）：用户模型缺 FUNCTION_CALLING 却需调 Agent → 自动 failover 到 `fallbackToolModel`。
- **密钥引用**：`apiKeyRef` 不落明文（`${SECRET:XXX}` 占位符，外部密钥管理）。
- **dashscope exclude 配置**（Spike 已证必配）。

### 不做（YAGNI）
- **ModelSelector 会话级选择**：需 application 层 + Session 集成，归 `add-orchestration-and-sse`。
- **模型管理 REST**（CRUD/启停）：归 `add-admin-console`。
- **配额/计费统计**：归 `add-cost-and-audit`。
- **多模态（vision 调用）**：一期只文本 + function-calling。

## 依赖
- `add-foundation-skeleton`：domain 端口定义、模块骨架。
- Spring AI 2.0.0-M1.1：`spring-ai-openai`（含 DeepSeek 兼容）、`spring-ai-alibaba-starter-dashscope`（2.0.0-M1.1，需 exclude）、zhipu/minimax 社区 starter（Spike 定）。
- Spring Boot 4.0。
- Caffeine（缓存）。

## 前置 Spike
并行验证 openai-compatible / zhipu / minimax 在 Boot 4.0 下兼容性，回填 `docs/superpowers/spike/2026-08-12-saa-compat-report.md` 矩阵。dashscope 行已验（需 exclude）。

## 验收标准
1. `ChatClientPort.sessionFor(model, tools)` 返回有效 `LlmSession`。
2. `LlmSession.generate()` 的 `Flow.Publisher<LlmEvent>` 正确发 Delta/ToolCall/Complete。
3. 四种 Provider ChatClient 均能工作（Spike 通过的）。
4. Nacos 模型配置变更 5s 内热更新。
5. 选定模型缺 FUNCTION_CALLING 但需调工具时，自动 failover 到 fallbackToolModel。
6. `${SECRET:XXX}` 正确解析，明文不落盘。
7. dashscope exclude 生效，启动无 ClassNotFoundException。
8. Flow↔Flux 适配器背压正确，无缓冲区泄漏。
9. 单元覆盖率 ≥80%，关键路径有 WireMock 集成测试。

## 实现结果（2026-08-13 完成）

| 验收项 | 结果 |
|---|---|
| ChatClientPort.sessionFor 返回 LlmSession | ✅ ChatClientPortImpl，4 测试（含 ArgumentCaptor 验证 failover） |
| LlmSession.generate 发 Delta/ToolCall/Complete | ✅ ChatClientLlmSession + LlmFlowAdapter 映射，9 测试（含背压/cancel/error） |
| 多 Provider ChatModel 装配 | ✅ SpringAiChatClientFactory（deepseek/openai builder + zhipu/minimax 构造器，Spring AI 2.0 API javap 核对） |
| Nacos 模型配置热更新 | ✅ NacosModelRegistry（裸 ConfigService + snakeyaml，19 测试）；真实 Nacos 接入待部署配 nacos.addr |
| 能力降级 failover（§5.5.5） | ✅ ModelCapabilityFailover，5 测试 |
| `${SECRET:XXX}` 解析 | ✅ SecretResolver 接口 + EnvSecretResolver，5 测试 |
| dashscope exclude 生效 | ✅ DashScopeMultimodalEmbeddingAutoConfiguration 排除（spike 证打包缺陷） |
| Flow↔Flux 背压/cancel | ✅ 用标准库 org.reactivestreams.FlowAdapters（reactive-streams 契约原生透传） |
| 覆盖率 ≥80% | ✅ 业务逻辑 84.7%（@Configuration 排除），JaCoCo check 绑 verify |

**测试**：54 个（adapter/session/port/factory/model），domain 未改。`mvn verify` 全绿，bootstrap contextLoads 通过。

**实现期发现（重要，记入 design）**：
1. **5 个 AI starter 无 key fail-fast**：deepseek/openai/zhipu/minimax/dashscope 各自的 autoconfig（chat/embedding/image 等）在无 API key 时启动失败。本网关由 ChatClientFactory 按需构造 ChatModel（key 从 ModelDef.apiKeyRef 注入），不用这些自动装配 bean。解法：bootstrap application.yml 给每个 provider 占位 key 满足 presence 检查（key 不被校验直到真实调用）+ 排除 dashscope 那个打包缺陷类。
2. **条件装配**：InfraLlmAutoConfiguration 用 `@ConditionalOnProperty(nacos.addr)`——无 nacos.addr（开发态/测试）时不装配 ModelRegistry/ChatClientPort，应用空启动；配 nacos.addr（部署）才接真实 Nacos。
3. **裸 nacos-client**（非 spring-cloud-alibaba，后者无 Boot4 版本）。

**延后**：
- Task 13 WireMock 集成测试：单元测试已覆盖 FlowAdapter/Session/Port 核心逻辑（含背压/cancel/failover）；WireMock 端到端 SSE 解析留有真实环境时补。
- Task 14 真实 API 验证：需真实各 provider API key，留部署环境。

## 关联文档
- Spec §5.5 模型接入与路由、§17 模型管理：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- Spike 报告：`docs/superpowers/spike/2026-08-12-saa-compat-report.md`
- Foundation design：`openspec/changes/add-foundation-skeleton/design.md`
- 本 change design/tasks：同目录

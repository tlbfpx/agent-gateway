# Tasks: A2A 协议客户端 + Nacos 发现（add-a2a-and-discovery）

> **任务清单视图**。详细 step 待 `writing-plans` 产出。实现时遵循 `AGENTS.md`：独立模块并行、同文件串行。

## 前置 Spike
- [x] **Task 1: Nacos A2A 兼容性 Spike**
  - 目标：验证 Nacos 3.x A2A Registry + Boot 4.0 兼容性
  - 完成判据：输出 `docs/superpowers/spike/2026-08-13-nacos-a2a-compat-report.md`；确认 nacos-client GAV + Netty 版本兼容；A2A Registry API 可用或替代方案；testcontainers/WireMock 方案；SAA 是否提供 A2A 客户端封装
  - **状态**：✅ DONE - nacos-client 3.3.0-BETA 内置完整 A2A API（`AiService`/`NacosAgentCardCacheHolder`/`AbstractNacosAgentCardListener`），与 Boot 4.0 无依赖冲突，testcontainers 用 GenericContainer + 官方镜像

## gateway-infra-nacos 模块
- [ ] **Task 2: 模块骨架 + nacos-client 依赖**
  - 判据：pom 配置（nacos-client 3.3.0-BETA）；`mvn -pl gateway-infra-nacos compile` 成功；依赖方向正确（只依赖 domain）
- [ ] **Task 3: NacosAiService 封装 + 模型映射**
  - 判据：复用内置 `AiService`/`NacosAgentCardCacheHolder`；mapper（Nacos AgentCard → domain AgentCard）；单元测试（Mock Nacos 响应）
- [ ] **Task 4: 内置监听器适配 + 定时拉取兜底**
  - 判据：复用内置 `AbstractNacosAgentCardListener` 接收 `AgentCardChangedEvent`；@Scheduled 60s 全量拉取；单元测试（推送、定时执行）
- [ ] **Task 5: AgentCardPort.snapshot() 实现**
  - 判据：适配内置缓存返回不可变 List（防御拷贝）；单元测试（空/单条/多条）
- [ ] **Task 6: AgentCardPort.watch() 实现（推送优先）**
  - 判据：SubmissionPublisher 包装内置监听器事件；单元测试（首次订阅、多次变更、多订阅者隔离）
- [ ] **Task 7: Nacos 不可达降级**
  - 判据：连接失败不抛异常、保持上次缓存；告警 + 指标 `nacos.unreachable`；集成测试（Nacos 宕机）

## gateway-infra-a2a 模块
- [ ] **Task 8: 模块骨架 + HTTP/SSE 客户端依赖**
  - 判据：pom（WebClient 或 java.net.http，Spike 定）；compile 成功；依赖方向正确
- [ ] **Task 9: SSE→Flow 适配器**
  - 判据：FlowAdapters.toPublisher/fromSSE；单元测试（正常/错误/中断 SSE）
- [ ] **Task 10: A2A JSON-RPC 客户端**
  - 判据：A2AClient.invoke 构建 POST /a2a/invoke/{agentName} 请求；单元测试（序列化、请求头）
- [ ] **Task 11: ToolEvent 映射（A2A Event→ToolEvent）**
  - 判据：映射表（design §1.1）；单元测试（delta/complete/error/未知）
- [ ] **Task 12: ToolPort.invoke() 实现**
  - 判据：返回 Flow.Publisher<ToolEvent>；集成测试（WireMock A2A SSE，验证 ToolEvent 序列）
- [ ] **Task 13: 超时/重试/降级**
  - 判据：a2a.timeout=30s；连接失败重试 1 次；错误码映射（design §4）；集成测试（超时、5xx 重试、4xx 不重试）

## 测试与集成验证
- [ ] **Task 14: 并发/流式专项测试**
  - 判据：多线程并发 invoke 无竞态；流式中断 Error 正确；多订阅者互不干扰
- [ ] **Task 15: 覆盖率门禁**
  - 判据：infra-nacos / infra-a2a 各 jacoco ≥80%；`mvn clean test` 全绿
- [ ] **Task 16: 依赖方向负向断言**
  - 判据：infra-nacos 不依赖 application/interfaces/api/bootstrap/infra-a2a；infra-a2a 不依赖 application/interfaces/api/bootstrap（grep 无输出）
- [ ] **Task 17: 端到端集成验证**
  - 判据：testcontainers GenericContainer + Nacos 官方镜像；snapshot() 返回 Nacos 注册的 AgentCard；invoke() 调通 WireMock A2A；SSE 正确解析为 ToolEvent 序列

## 交接
- [ ] **Task 18: 交接「编排核心」change**
  - 判据：ToolPort/AgentCardPort 实现文档化（javadoc + 使用示例）；明确能做什么（发布快照、调 A2A）vs 不做（路由策略、ToolRegistry 消费）

> 任务间可微调（如 Task 9/10 可并行），整体顺序：Spike → Nacos → A2A → 测试 → 交接。

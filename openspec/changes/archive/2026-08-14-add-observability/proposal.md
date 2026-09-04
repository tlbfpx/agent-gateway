# Proposal: 可观测性（add-observability）

> **状态：✅ 已完成**（2026-08-14）。

## 变更概述

实现 `gateway-infra-observability`：domain `ObservabilityHooks` 端口 + Micrometer 指标实现 + ChatOrchestrator 埋点 + Actuator 端点。spec §7。让网关可观测（指标/调用链），为生产化打基础。

## 动机

无监控的网关不能上生产。编排核心已实现但无指标埋点——无法知道调用量/延迟/错误率/Agent 命中分布/成本。本 change 补可观测性核心。

## What / 范围

### 做
- **domain `ObservabilityHooks` 端口**（零框架，SPI）：记录 chat 请求/Agent 调用/LLM/token 的指标事件。
  ```java
  interface ObservabilityHooks {
      void onChatRequest(String tenant, String user, String model, String channel);
      void onChatComplete(String tenant, String model, long latencyMs, boolean success);
      void onAgentInvoke(String tenant, String agentName, String model);
      void onAgentComplete(String tenant, String agentName, long latencyMs, boolean success);
      void onTokens(String tenant, String model, long tokensIn, long tokensOut);
      void onError(String tenant, String code);
  }
  ```
- **`gateway-infra-observability`**：
  - `MicrometerObservabilityHooks`：用 Micrometer（MeterRegistry）实现指标（spec §7.2 指标体系）。
  - 条件装配（有 MeterRegistry bean 时启用；无则 NoopObservabilityHooks）。
- **ChatOrchestrator 埋点**：在编排关键点（chat request/complete、agent invoke/complete、error）调 ObservabilityHooks。
- **Actuator**：bootstrap 加 spring-boot-starter-actuator，暴露 `/actuator/metrics`（Micrometer 指标端点）。

### 不做（YAGNI / 运维范畴）
- **OTel Collector 部署**：运维侧；网关经 Actuator/Micrometer 暴露指标，Collector 拉取是部署配置。
- **spring-ai-alibaba-admin 应用**：独立应用，运维部署；本 change 让网关指标可被采集。
- **分布式 Trace（span 树）**：spec §7.3；需 OTel SDK 完整集成，二期。一期先 Micrometer 指标。
- **成本核算**：add-cost-and-audit change（本 change 只埋 token 指标）。

## 验收标准
1. domain ObservabilityHooks 端口定义（零框架）。
2. MicrometerObservabilityHooks 实现 spec §7.2 指标（chat.requests/latency、agent.invocations/latency/errors、llm.tokens、error）。
3. ChatOrchestrator 关键点埋点（orchestrate 开始/完成、agent 调用、error）。
4. bootstrap actuator 暴露 /actuator/metrics，指标可查。
5. 覆盖率 ≥80%，domain 未改（除新增端口）。
6. `mvn clean test` 全绿。

## 关联文档
- spec §7 可观测性：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置：编排核心（ChatOrchestrator 已就绪）

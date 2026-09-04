# Tasks: Auto Router（round10-auto-router）

- [x] **A.1** 领域接口：RoutingStrategy enum / RoutingPolicy record / Candidate record / RouteDecision record / RoutingMetricsSnapshot record / RoutingContext record / RoutingPort interface
- [x] **A.2** 领域测试：RoutingPolicyTest 5 例（empty/negative weight/zero weight/正常/序列化名一致）
- [x] **B.1** 应用层：AutoRouter 用例（编排 RoutingPort + 异常转换）
- [x] **B.2** 应用测试：AutoRouterTest 8 例（LOWEST_COST / FASTEST / QUALITY / WEIGHTED / 超 budget / fallback 链 / 决策审计 / 异常转换）
- [x] **C.1** 基础设施：DefaultRoutingService（RoutingPort 实现 + 4 策略算法 + fallback chain）
- [x] **C.2** 基础设施：MicrometerRoutingMetricsAdapter + CaffeineRoutingWindowStore
- [x] **C.3** 基础设施测试：MicrometerRoutingMetricsAdapterTest 5 例（successRate / p50 / avgCost / 空指标 / 多模型）
- [x] **D.1** Web API：RoutingAdminController（5 端点）+ 单元测试
- [x] **E.1** 配置：RoutingAutoConfiguration + RoutingProperties + bootstrap AutoConfiguration imports
- [x] **F.1** ChatOrchestrator 集成：setAutoRouter + selectModel 前置分支（Optional，向后兼容）
- [x] **G.1** UI：pages/Routing.tsx + lib/api/routing.ts + Sidebar / Routes 注册
- [x] **G.2** UI 测试：routing-live-contract.test.ts（CRUD / dry-run / metrics）
- [x] **H.1** OpenSpec 4 件套：proposal / design / spec / tasks（本文件）

## 验收门禁

- [x] 域零框架（GW-RT-014）：domain/routing 无 SLF4J / Spring / Micrometer 依赖
- [x] 单模块 verify：`mvn -pl gateway-domain,gateway-application,gateway-infra-llm,gateway-interfaces,gateway-bootstrap -am test` BUILD SUCCESS
- [x] 依赖方向：application 不依赖 infra/interfaces（verify.sh 第 [3/3] 步）
- [x] Spec 条款 GW-RT-001 ~ GW-RT-018 全部覆盖（含测试引用）
- [x] 前端测试：`cd agent-gateway-ui && npx vitest run` 全绿

## 后续（P2/P3）

- GW-RT-008 增强：fallback chain 支持 0-cost ceiling（仅 latency ceiling）
- GW-RT-013 增强：p95 / p99 latency 暴露
- GW-RT-018 增强：metrics 按 tenant 切片（多租户隔离）
- AutoRouter 决策接入 model-level RBAC（principal.allowedModels 过滤 candidates）
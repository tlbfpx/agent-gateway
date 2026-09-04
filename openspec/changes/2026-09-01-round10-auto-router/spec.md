# Spec: Auto Router（可测试条款）

#### GW-RT-001 配置开关
**MUST**：`gateway.routing.enabled=false`（默认 true）时 AutoRouter 不装配；ChatOrchestrator 走原 selectModel 路径，行为与未启用完全一致。
**测试**：ChatOrchestratorRoutingTest.disabledBypassesAutoRouter。

#### GW-RT-002 策略枚举完整
**MUST**：`RoutingStrategy` 含 4 个值：LOWEST_COST / FASTEST_FIRST_TOKEN / QUALITY_FIRST / WEIGHTED；序列化名等于枚举名。
**测试**：RoutingStrategyTest.allValuesPresent。

#### GW-RT-003 RoutingPolicy 校验
**MUST**：`RoutingPolicy` 必填：strategy + candidates（至少 1 个）+ fallbackChain（可空）。candidates 全部 weight ≤ 0 → IllegalArgumentException。candidates 为空 → IllegalArgumentException。
**测试**：RoutingPolicyTest.emptyCandidatesRejected / negativeWeightRejected。

#### GW-RT-004 LOWEST_COST 决策
**WHEN** strategy=LOWEST_COST **THEN** AutoRouter 选 avgCostCents 最小的候选（且 cost ≤ candidate.costCeilingCents）。
**测试**：AutoRouterTest.lowestCostPicksCheapestModel。

#### GW-RT-005 FASTEST_FIRST_TOKEN 决策
**WHEN** strategy=FASTEST_FIRST_TOKEN **THEN** AutoRouter 选 p50LatencyMs 最小的候选（且 latency ≤ candidate.latencyP99CeilingMs）。
**测试**：AutoRouterTest.fastestFirstTokenPicksLowestLatency。

#### GW-RT-006 QUALITY_FIRST 决策
**WHEN** strategy=QUALITY_FIRST **THEN** AutoRouter 选 successRate × min(sampleCount/100, 1.0) 置信度得分最高的候选。
**测试**：AutoRouterTest.qualityFirstPicksHighestSuccess。

#### GW-RT-007 WEIGHTED 决策
**WHEN** strategy=WEIGHTED **THEN** AutoRouter 按 Candidate.weight 比例随机选；随机过程 deterministic 给定 seed。
**测试**：AutoRouterTest.weightedPicksByWeights / weightedDistributionMatchesWeights。

#### GW-RT-008 超 budget 行为
**WHEN** 所有候选均超 cost/latency ceiling **THEN** AutoRouter 走 fallbackChain（顺序尝试），fallbackChain 也失败 → 抛 RoutingPolicyExhaustedException。
**测试**：AutoRouterTest.allCandidatesOverBudgetTriggersFallback / fallbackChainExhaustedThrows。

#### GW-RT-009 候选过滤
**MUST**：策略决策前先过滤掉超 candidate.costCeilingCents / candidate.latencyP99CeilingMs 的候选；过滤后候选为空 → 走 fallbackChain。
**测试**：AutoRouterTest.overBudgetCandidateFiltered。

#### GW-RT-010 决策审计字段
**MUST**：`RouteDecision` 必含 chosenModel / rationale（包含 strategy + 选中的指标值）/ alternativesConsidered（被剔除的候选 + 原因）。
**测试**：AutoRouterTest.routeDecisionContainsRationale。

#### GW-RT-011 集成 ChatOrchestrator
**WHEN** AutoRouter bean 存在且 ChatRequest 未显式指定 model **THEN** ChatOrchestrator.selectModel() 走 AutoRouter.decide()；当 AutoRouter 抛 RoutingPolicyExhaustedException → emit error RATE_LIMITED 风格（NO_FALLBACK）。
**测试**：ChatOrchestratorRoutingTest.autoRouterChosenWhenNoExplicitModel / exhaustedFallbackFailsRequest。

#### GW-RT-012 向后兼容
**WHEN** AutoRouter bean 不存在（未装配）**THEN** ChatOrchestrator 走原硬选逻辑（request.modelOpt → session.model → defaultModel），完全等价。
**测试**：ChatOrchestratorRoutingTest.legacyPathWhenAutoRouterAbsent。

#### GW-RT-013 指标聚合
**MUST**：`MicrometerRoutingMetricsAdapter.snapshot(model)` 返回 RoutingMetricsSnapshot：successRate = successCount / totalCount；p50LatencyMs = Timer.percentile(0.5)；avgCostCents = costCounter.mean()。
**测试**：MicrometerRoutingMetricsAdapterTest.successRateCalculated / p50LatencyExtracted。

#### GW-RT-014 域零框架
**MUST**：domain/routing 类无 Spring / 数据库 / Micrometer 依赖；纯 record + interface + 静态工具。
**测试**：ArchUnitTest 或 module 依赖方向断言。

#### GW-RT-015 Web API 完整
**MUST**：`RoutingAdminController` 暴露 5 个端点：listPolicies / upsertPolicy / deletePolicy / dryRun / metrics；返回 200 + record JSON。
**测试**：RoutingAdminControllerTest.allEndpointsContract。

#### GW-RT-016 策略热更新
**MUST**：`POST /v1/admin/routing/policies` 立即更新内存策略；新决策请求立即按新策略执行。
**测试**：RoutingAdminControllerTest.policyUpdateTakesEffectImmediately。

#### GW-RT-017 试运行（dry-run）
**MUST**：`POST /v1/admin/routing/decide` body 含 `{candidateMetrics:[...], context:{...}, policyHint:{...}}`；返回 RouteDecision（不实际调度 LLM）。
**测试**：RoutingAdminControllerTest.dryRunReturnsDecisionWithoutSideEffects。

#### GW-RT-018 metrics 端点实时
**MUST**：`GET /v1/admin/routing/metrics` 返回所有模型的 RoutingMetricsSnapshot（含 modelId / successRate / p50LatencyMs / avgCostCents / sampleCount / windowStart）。
**测试**：RoutingAdminControllerTest.metricsReturnsAllModels。
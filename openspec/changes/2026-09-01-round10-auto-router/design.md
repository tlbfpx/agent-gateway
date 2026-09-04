# Design: Auto Router（cost / latency / quality 三维策略自动选模型）

## 1. 领域模型（gateway-domain/routing）

| 类型 | 名称 | 说明 |
|------|------|------|
| enum  | RoutingStrategy | LOWEST_COST / FASTEST_FIRST_TOKEN / QUALITY_FIRST / WEIGHTED |
| record | RoutingPolicy | id / strategy / candidates(List<Candidate>) / fallbackChain(List<ModelId>) |
| record | Candidate | modelId / weight / costCeilingCents(Nullable) / latencyP99CeilingMs(Nullable) |
| record | RouteDecision | chosenModel / rationale / alternativesConsidered(List<Candidate>) |
| record | RoutingMetricsSnapshot | modelId / successRate(0-1) / p50LatencyMs / avgCostCents / sampleCount / windowStart |
| record | RoutingContext | tenant / promptTokens / strategy（可覆盖 policy 默认） |
| interface | RoutingPort | decide(policy, candidates, metrics, ctx) → RouteDecision |

**域零框架**（GW-RT-014）：domain/routing 类无 Spring / DB / Micrometer 依赖；仅 java.*。

## 2. 应用层编排（gateway-application/routing）

`AutoRouter` 用例：
1. 接收 RoutingPolicy + List<RoutingMetricsSnapshot> + RoutingContext
2. 调用 RoutingPort.decide() → RouteDecision
3. 返回 RouteDecision（含 chosenModel + rationale）

**集成点**：ChatOrchestrator.selectModel() 前置插入：
- 若 autoRouter == null → 走原 selectModel（向后兼容）
- 若 autoRouter != null 且 request 未显式指定 model → 调 autoRouter.decide()，结果替代原 session.model() 选择

向后兼容关键：用 `Optional<AutoRouter>` 字段注入（Spring 自动装配 Optional bean）。

## 3. 基础设施（gateway-infra-llm/routing）

| 类 | 职责 |
|------|------|
| DefaultRoutingService | RoutingPort 实现：四种策略算法 + fallback 链路 |
| MicrometerRoutingMetricsAdapter | 从 MeterRegistry 拉 Counter/Timer，产出 RoutingMetricsSnapshot |
| CaffeineRoutingWindowStore | 5min 滑动窗口 fallback（无 Micrometer 时启用） |
| RoutingAutoConfiguration | Spring 自动装配（@ConditionalOnProperty gateway.routing.enabled） |

**关键算法**（DefaultRoutingService.decide）：
- LOWEST_COST：选 avgCostCents 最小的候选（且 ≤ costCeilingCents）
- FASTEST_FIRST_TOKEN：选 p50LatencyMs 最小的候选（且 ≤ latencyP99CeilingMs）
- QUALITY_FIRST：选 successRate * sampleCount 置信度最高的候选
- WEIGHTED：按 Candidate.weight 加权随机；过滤超 ceiling 的候选

超 ceiling 候选：直接跳过；若全部超 → 走 fallbackChain。

## 4. Web API（gateway-interfaces/routing）

`RoutingAdminController`（`@RestController`）：
- `GET /v1/admin/routing/policies` → List<RoutingPolicy>
- `POST /v1/admin/routing/policies` → RoutingPolicy（创建/更新）
- `DELETE /v1/admin/routing/policies/{id}` → 204
- `POST /v1/admin/routing/decide` → RouteDecision（dry-run，body 含 ctx）
- `GET /v1/admin/routing/metrics` → List<RoutingMetricsSnapshot>

请求 / 响应均为 record JSON（Jackson 默认序列化为 snake_case 字段名由属性决定；Policy 用 record 简洁实现）。

## 5. 配置（gateway-bootstrap）

```yaml
gateway:
  routing:
    enabled: true
    default-strategy: LOWEST_COST
    window-minutes: 5
```

`RoutingAutoConfiguration`：当 `gateway.routing.enabled=true` 时装配：
- DefaultRoutingService（RoutingPort 实现）
- RoutingPolicyStore（内存 Map）
- MicrometerRoutingMetricsAdapter（MeterRegistry 注入）
- RoutingController（注入到 gateway-interfaces 模块注册）

## 6. UI（agent-gateway-ui/src/pages/Routing.tsx）

页面布局：
- 顶部：策略列表（id / strategy / 候选数 / fallback 链）
- 左侧：创建策略表单（strategy select / 候选 model tags / 权重 / ceiling / fallback chain）
- 右侧：试运行面板（输入 strategy hint / token 数 → 显示 chosenModel + rationale）
- 底部：模型级实时指标表（modelId / successRate / p50 / avgCost / samples）

`lib/api/routing.ts`：routingApi.{listPolicies / upsertPolicy / deletePolicy / dryRun / metrics}

## 7. 依赖方向

- domain/routing → 纯 java.*（零框架）
- application/routing → domain/routing
- infra-llm/routing → domain/routing（实现 RoutingPort）
- interfaces/routing → domain/routing + application/routing + infra-llm/routing
- bootstrap → interfaces/routing + infra-llm/routing（条件装配）

verify.sh 第 [3/3] 步检查 application 不依赖 infra/interfaces：routing 用例放在 application/routing，
仅依赖 domain，无 infra；保持原架构铁律。
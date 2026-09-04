# Proposal: Auto Router — 自动选模型（cost / latency / quality 三维策略）

> **状态**：Round 10 新能力，代码 + 测试 + UI 全栈落地
> **来源**：Round 9 报告 §6 候选 — Auto Router 让网关从「灰度路由」升级为「智能选模型」

## 动机

当前 ChatOrchestrator.selectModel() 仅按「请求指定 / 会话绑定 / 默认模型」硬选，**没有利用历史指标**：
- 用户想"便宜一点"→ 不知道哪个模型 cost 低
- 用户想"快一点"→ 不知道哪个模型 p50 latency 短
- 用户想"质量好一点"→ 不知道哪个模型 success rate / completion token 高

LiteLLM Router、Portkey AI Gateway、OpenRouter 都已落地 simple-routing / cost-based / rate-limit-aware 策略。

本变更引入 **Auto Router**：基于 cost / latency / quality 三维 + 滑动窗口历史指标自动选模型，
让用户在策略层声明「我要好/便宜/快」目标，网关自动决策。

## What

### 领域接口（gateway-domain/routing）
- `RoutingStrategy` enum：`LOWEST_COST` / `FASTEST_FIRST_TOKEN` / `QUALITY_FIRST` / `WEIGHTED`
- `RoutingPolicy` record：strategy + 候选 model + 权重 + cost ceiling + latency p99 ceiling + fallbackChain
- `RoutingPort` 接口：decide(candidates, ctx) → RouteDecision
- `RouteDecision` 值对象：chosenModel + rationale + alternativesConsidered
- `RoutingMetricsSnapshot`：模型级 success rate / p50 latency / avg cost（5min 滑动窗口）

### 应用层（gateway-application/routing）
- `AutoRouter` 用例：组合 RoutingPort + RoutingMetricsSnapshot → 决策
- 集成到 ChatOrchestrator.selectModel() 前置（Optional 注入，向后兼容）

### 基础设施（gateway-infra-llm/routing）
- `MicrometerRoutingMetricsAdapter`：从 MeterRegistry 聚合 success rate / p50 latency / avg cost
- `CaffeineRoutingWindowStore`：5min 滑动窗口（fallback，MeterRegistry 不可用时启用）
- `DefaultRoutingService`（实现 RoutingPort）：四种策略的决策算法

### Web API（gateway-interfaces/routing）
- `RoutingAdminController`：
  - GET / POST `/v1/admin/routing/policies` — 策略 CRUD
  - POST `/v1/admin/routing/decide` — 试运行（dry-run，给定 ctx 返回决策）
  - GET `/v1/admin/routing/metrics` — 模型级实时指标

### 配置（gateway-bootstrap）
- `RoutingProperties`：`gateway.routing.enabled=true`，默认策略 `LOWEST_COST`
- `RoutingAutoConfiguration`：条件装配所有 bean

### UI（agent-gateway-ui/src/pages/Routing.tsx）
- 策略列表 + 创建表单（strategy / 候选模型 / 权重 / cost ceiling / latency p99 ceiling / fallback chain）
- "试运行"输入 ctx 看决策
- 模型级实时指标（成功率 / 延迟 / 成本）

## Non-goals

- 不做 cross-region / multi-DC 路由（留二期）
- 不做 LLM-based 路由决策（仅基于规则 + 历史 metrics）
- 不做预算硬上限拦截（与 budget.downgrade 解耦，组合留给上层）
- 不做 A/B test 实验框架（仅基于稳态指标）
- 不替换现有 ChatOrchestrator.selectModel() 主路径（Optional 注入；旧逻辑完全保留）

## 验收

- 后端：domain/routing + infra-llm/routing + application/routing 测试全过
- 集成：ChatOrchestrator 在 RoutingPolicy 启用时改走 AutoRouter，禁用时走原硬选
- UI：Routing 页面渲染策略列表 + 创建表单 + 试运行 + 模型级指标
- 配置：`gateway.routing.enabled=true`（默认 true）
- 测试覆盖：四种策略 + fallback chain + 超 budget 行为 + 决策正确性
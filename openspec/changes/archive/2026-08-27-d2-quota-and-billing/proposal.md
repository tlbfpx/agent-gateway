# Proposal: 多租户配额 + 成本计费（d2-quota-and-billing）

> **状态**：📝 阶段二待评审（OpenSpec 立项稿）
> **范围聚焦**：spec §21 商业化闭环 + §16.2 租户隔离边界 + §7.2 单一数据源原则
> **术语锚点**：spec §13.4（错误码段位）/ §21.1~21.7 / §7.2 / §5.5.2（模型单价）

## 变更概述

本 change 实现网关的「治理-商业化闭环」：`UsageRecord → CostRecord → Budget` 三 record 类型化 + QuotaGate 前置拦截 + 三档超额策略（ALERT/THROTTLE/SUSPEND）+ 实时成本核算 + Chargeback 报表导出 + 预算告警链路。一期补齐 D 路线总览 §2.2 D2 三大缺口（recordUsage 主调用链未接线 / token 硬编码 1500 / 预算告警缺失），与 D1 RBAC 解耦但复用其租户隔离边界。

## 动机

D2 决策矩阵得分 3.8（与 D1 并列最高），工作流上紧随 D1（roadmap §4.1 D2 依赖 D1 租户隔离）。三件真问题：

1. **token 单价核算的「单一数据源」原则未落地**。spec §21.3 要求每次 LLM 调用结束 → `ObservabilityHooks.llm.tokens{in,out}` → 异步 MQ → 限流 + 成本核算共用数据源。当前 `ChatOrchestrator` 完全没接 `recordUsage`（grep 无调用点），`AdminMetricsController:168` 直接 `long tokens = 1500L; // 估算口径（审计无 token 字段）`，导致成本看板数据不准、预算告警失效。
2. **租户级硬上限缺位**。spec §16.2 的 `Quota` record 仅有 `qpsLimit / dailyTokenBudget / modelSpecificLimits`，全部在 `RateLimiter` 令牌桶内；**没有金额阈值、没有超额后动作可配**。一旦某租户被刷，唯一兜底是 QPS/Token 限流——而成本不可逆（事后才发现）。
3. **预算告警链路缺失**。spec §21.4 把超限处理定死为「Token 超 → 429 拒绝」「金额超 → 告警不阻断」；当前 `Budget` record 在 `CostRepository` 仅占位（`InMemoryCostRepository` 3 个测试自身调用），既无告警触发，也无预算校验异步任务，更无 D2 阶段一路线总览 §2.2 要求的「ALERT/THROTTLE/SUSPEND 三档可配」。

D2 的核心痛点：**配额只到「计数器」、计费只到「核算表」，二者未与「管理动作 + 账单语义」贯通**。本 change 划清边界：**配额是前置门**（拦截 + 动作），**计费是后置账**（记录 + 报表 + 对外）。

## What / 范围

### 做（What）

- **能力 1 — UsageRecord/CostRecord/Budget/Invoice 类型化**（spec §21.2）：5 个 record 落地 `gateway-domain/billing`；`Invoice / InvoiceLineItem / InvoiceStatus / ExportFormat` 新增。强类型 `TenantId / UserId / ModelId` 复用既有（与 D1 共用 shared 包）。
- **能力 2 — QuotaGate 前置拦截**（spec §16.2 + §8.3）：编排层在 ChatOrchestrator 调用 LLM 前先查 `QuotaPort.check()`；零侵入通过 `Orchestrator` 已有结构嵌入（不动 `AuthorizationService` 既有决策路径）。配额计数器三维：`REQUEST / MODEL_TOKEN / MONEY`。
- **能力 3 — 实时计费 + 单一数据源**（spec §21.3）：`BillingPort.recordUsage(UsageRecord)` 接 `ObservabilityHooks.llm.tokens{in,out}` 异步回调；成本 = `tokens × ModelDef.costPer1k{In,Out}`（spec §5.5.2），落账时同时快照 `unit_price_in / unit_price_out`，模型单价变更后历史账单可复现。
- **能力 4 — 超额策略三档可配**（spec §21.4 + D2 阶段一路线总览 §2.2）：`ALERT`（告警不阻断）/ `THROTTLE`（降速基线 QPS 至 X%，限速期 N 分钟）/ `SUSPEND`（写 `tenant.suspended=true` 推 Redis，AuthFilter 拒所有请求）。**SUSPEND 必须显式管理员动作** + 5 分钟冷静期；自动策略只到 THROTTLE（避免误停服）。
- **能力 5 — 预算告警 + Webhook 投递**：`UsageWriter` 落账累加 `currentDailyUsed/currentMonthlyUsed`，超 `alertThreshold.percent` 且未发过 → 触发告警（spec §21.4 邮件 + Webhook，扩展 D1 GW-RBAC 触发路径）；事件经 `WebhookEventBridge` 投递。
- **能力 6 — Chargeback 报表导出**（spec §21.5）：`GET /v1/admin/billing/costs` 四维（tenant/model/agent/date）+ `GET /v1/admin/billing/usage/export` CSV 导出 + `GET/POST/PUT /v1/admin/billing/budgets` 预算 CRUD；错误码 `GW-4301~4306`（沿用 spec §21.6 已规划 4301~4303，新增 4304~4306）。
- **能力 7 — 既有 `AdminMetricsController` 硬编码 1500 替换**：移除 `long tokens = 1500L` 占位（line 168），改为读 `BillingPort.queryUsage(...)` 真实数据；E2E 主流程断言「AdminMetrics 显示数字 == LLM 调用实际 token」。
- **能力 8 — 与 D1 RBAC 接口边界零破坏**：`AuthorizationService` 接口签名不变（D1 已固化 6 方法 + 既有 6 条测试零修改红线）；D2 仅复用 `TenantId / UserId` 强类型 + D1 既有 `RbacChangePublisher` 通道（预算变更复用 RBAC 变更发布通道，零新基础设施）。

### 不做（Non-goals，二期 / YAGNI）

- **预付费卡密 + 储值账户扣减**：spec §21.7 二期。
- **增值税发票 / 税务字段**：spec §21.7 二期；本期固定 CNY 单币种 + 字典表预留 `currency` 字段。
- **多币种实时汇率换算**：spec §21.7 二期。
- **部门分摊 / 审批流 / 智能异常检测**：spec §21.7 二期。
- **用户级预算**（vs 一期仅租户级）：spec §21.7 二期；`Budget` record 字段预留 `UserId user` 但不接管理 REST。
- **JPA 实现 / Flyway 迁移**：design §6 占位清单明示留二期（与 D1 选 A 一致）。
- **AuthFilter SUSPEND 拦截落地**：本期 SUSPEND 仅写 `tenant.suspended` 标志位 + Redis 缓存；AuthFilter 集成留二期（避免 D1 已归档 AuthFilter 重新修改）。
- **MQ 真实异步分发**：一期 InMemory 队列（与 D1 `NacosRbacChangePublisher` 占位策略一致），二期接 RabbitMQ/Kafka。

## 关键决策点（≥4 个决策点，每个含 A/B 对比表格 + 推荐理由）

### 决策点 D-1：配额计数器实现

| 方案 A：内存滑窗（Caffeine） | 方案 B：Redis 原子计数（INCRBY + TTL，§8.3 已用 Lua） | **推荐 B** |
|---|---|---|
| 单实例高性能；网关无状态多实例（§8.5）下漏算 | 分布式精确；Redis 已是限流底座，扩 key 族零边际成本 | 与 §8.3 RateLimiter 复用；分布式必选 |

理由：spec §8.3 已用 Redis Lua 做限流，本期延续；配额是多实例共享状态，Redis 是唯一选项。

### 决策点 D-2：计费事件流

| 方案 A：同步写库（每请求 1 次 INSERT） | 方案 B：异步 MQ 落账（§21.3 既定） | **推荐 B** |
|---|---|---|
| 强一致但 LLM 高并发下打爆 PG | 落账可见性 ≤ 30s（spec §21.3 既定），主调用链零阻塞 | §21.3 单源原则明确「异步 MQ」；与 D1 `NacosRbacChangePublisher` 占位策略一致 |

理由：spec §21.3 已明确「异步 MQ」。本期 InMemory 队列实现，二期接 RabbitMQ。

### 决策点 D-3：多币种与汇率

| 方案 A：CNY 单币种 + 字典表预留 `currency` 字段 | 方案 B：多币种 + 汇率表 + 定时同步 | **推荐 A** |
|---|---|---|
| 简单；模型单价直接维护 CNY | 支持全球定价 | 国内主体（§0.1 决策 7）；汇率波动引入成本不确定性；`currency` 字段无痛升级 |

理由：spec §0.1 决策 7 国内主流模型；二期按需扩展。

### 决策点 D-4：超额动作执行点

| 方案 A：RateLimiter 内联（同一 Lua） | 方案 B：独立 QuotaGate（先判后放） | **推荐 B** |
|---|---|---|
| 性能最高但 Lua 膨胀 | 职责单一、易测；能承载租户级硬开关 SUSPEND | §21.4 三档策略需独立决策点；与 D1 RbacInflightPolicy 同架构 |

理由：D2 阶段一路线总览 §2.2 D2 推荐主线明确「独立 QuotaGate」；SUSPEND 是租户级硬开关，与 QPS/Token 限流职责不同。

### 决策点 D-5：AuthFilter SUSPEND 拦截（D2 §Non-goals 已声明留二期，此处仅说明设计意图）

| 方案 A：一期就接入 AuthFilter（修改 D1 已归档代码） | 方案 B：一期仅写 Redis 标志位，二期接入 | **推荐 B** |
|---|---|---|
| 全闭环 | 零侵入 D1 RBAC 已归档 | D1 既有 `AuthorizationServiceImplTest` 6 条零修改红线不可破；二期独立 change 接入 |

理由：D1 阶段四归档闸门 ④ 强约束「既有测试零修改」；接入 SUSPEND 是跨 D1+D2 大改动，独立 change 立项更稳。

## 错误码段分配

| 码 | 触发场景 | 段归属 | 状态 |
|---|---|---|---|
| `GW-4301` 账单查询参数非法 | spec §21.6 已规划；`GET /v1/admin/billing/costs` 缺字段/格式错 | GW-43xx | 沿用 |
| `GW-4302` 预算配置冲突（如日预算 > 月预算） | spec §21.6 已规划；`POST /v1/admin/billing/budgets` 校验失败 | GW-43xx | 沿用 |
| `GW-4303` 账单导出失败（对象存储不可用） | spec §21.6 已规划；`GET /v1/admin/billing/usage/export` 失败 | GW-43xx | 沿用 |
| `GW-4304` 配额硬上限触发，拒绝请求 | 本期新增；QuotaGate 返回 Rejected → HTTP 429 | GW-43xx | 新增 |
| `GW-4305` 租户被 SUSPEND，请求被拒 | 本期新增；QuotaGate 返回 Suspended → HTTP 403 | GW-43xx | 新增 |
| `GW-4306` 配额策略非法（policy 取值不在白名单） | 本期新增；`POST /v1/admin/billing/budgets` 校验 | GW-43xx | 新增 |

> **段位零冲突自检**：D1 占 GW-1xxx（1010~1013）+ GW-42xx（4204），D3 占 GW-5xxx，D4 占 GW-45xx/6xxx/7xxx；本 D2 占用 GW-43xx 4301~4306（spec §21.6 已规划段），与已用段零冲突（roadmap §3 已 Approved）。

## 与现有模块的关系

### 复用（不改）

- **`ObservabilityHooks` 接口**（`gateway-domain/observability/`）：`llm.tokens{in,out}` 已是单一数据源出点（§7.2）；D2 不改接口，在 `MicrometerObservabilityHooks` 实现里挂 `BillingPort.recordUsage(...)`。
- **`RateLimiter`**（`gateway-domain/iam/RateLimiter`，spec §8.3）：QPS/Token 维度已用 Redis；D2 QuotaGate 复用同一 Redis 实例（key 前缀区分：`gw:quota:*` vs `gw:ratelimit:*`）。
- **`WebhookEventBridge`**（A/C 阶段基础组件）：预算告警投递复用通道（D1 GW-RBAC-009 已用）。
- **`TenantId / UserId / ModelId / RbacChangePublisher`**（D1 既有）：强类型与变更发布通道直接复用。

### 扩展（新增文件，不改既有类）

- `gateway-domain/billing/` 新增：`UsageRecord / CostRecord / Budget / Quota / Invoice / InvoiceLineItem / InvoiceStatus / ExportFormat` 8 record + `BudgetType / AlertThreshold` 2 enum + `BillingPort / QuotaPort` 2 Port。
- `gateway-application/` 新增：`QuotaGate`（前置拦截）+ `BillingEngine`（落账 + 单价快照）+ `BudgetGuard`（异步校验 + 告警触发）。
- `gateway-infra-persistence/` 新增：`InMemoryBillingRepository / InMemoryQuotaRepository`（一期默认，二期 JPA 留口）。
- `gateway-interfaces/` 新增：`AdminBillingController`（4 端点）+ `AdminQuotaController`（预算 CRUD）。
- `agent-gateway-ui/` 新增：`pages/CostCenter/`（报表 + 预算管理）+ `pages/Budgets/`（预算阈值配置）。

### 重写（无）

- **不重写既有 `AdminMetricsController`**：删除 line 168 的 `long tokens = 1500L` 硬编码，改为读 `BillingPort.queryUsage()` 真实数据（行为变更，但接口契约不变，无破坏性）。
- **不动 D1 已归档代码**：`AuthorizationService` 接口 / `AuthorizationServiceImpl` / `AdminPolicyController` / `RbacFilter` 全部零修改（既有 6 条测试零修改红线）。

## 验收标准（≥5 条 SHALL 摘要，详见 spec.md）

1. **`GW-QUOTA-001`** UsageRecord 单一数据源：每次 LLM 调用结束时 `ObservabilityHooks.llm.tokens{in,out}` 触发的回调必须调用 `BillingPort.recordUsage`（无回调路径 → `AdminMetricsController` 显示真实数字，E2E 断言）。
2. **`GW-QUOTA-002`** QuotaGate 前置拦截：编排层在 LLM 调用前调 `QuotaPort.check(QuotaKey)`，超限返回 `Rejected` 并抛 `QuotaExceededException → HTTP 429 + GW-4304`。
3. **`GW-QUOTA-003`** 三档策略：同一租户不同维度可独立配（`REQUEST/MODEL_TOKEN/MONEY` × `ALERT/THROTTLE/SUSPEND`）；`SUSPEND` 必须显式管理员动作（5 分钟冷静期，自动策略只到 THROTTLE）。
4. **`GW-QUOTA-004`** 计费幂等可复现：`UsageRecord` 落库时快照 `unit_price_in / unit_price_out`，模型单价变更后历史账单金额可重算（不依赖当前单价）。
5. **`GW-QUOTA-005`** 告警不阻断主调用链：超阈值告警写入异步任务失败时，主调用链不抛异常；告警通过 `WebhookEventBridge` 投递（D1 GW-RBAC-009 复用通道）。
6. **`GW-QUOTA-006`** REST 契约：`/v1/admin/billing/{costs,usage/export,budgets}` 错误码 `GW-4301~4306` 段位零冲突。
7. **`GW-QUOTA-007`** 既有 6 条 `AuthorizationServiceImplTest` 零修改仍全绿（spec §归档闸门 ④）。
8. **`GW-QUOTA-008`** 既有 `AdminMetricsController` 不再有 `long tokens = 1500L` 硬编码（替换为 `BillingPort.queryUsage()`）。

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Redis 故障导致配额计数漂移 | 中 | 高（超额不被拦截） | §8.4 已要求 Redis AOF；故障时降级为"仅 ALERT"，**绝不进入 SUSPEND**（避免误停服） |
| 异步 MQ 积压导致报表延迟 | 中 | 中 | 队列长度指标 `billing.queue.lag`；超阈值告警；OLAP 主库保留 90 天可重放 |
| SUSPEND 误触发导致全租户停服 | 低 | 极高 | SUSPEND 必须显式管理员动作（管理后台二次确认 + 5 分钟冷静期）；自动策略只到 THROTTLE |
| 模型单价变更导致历史账单口径漂移 | 中 | 中 | `billing_records` 落库时快照 `unit_price_in / unit_price_out`，账单重算可复现 |
| 配额检查本身成为热点 | 低 | 中 | QuotaPort 内置本地预扣（in-process bucket）+ 异步回填 Redis，降低 Redis QPS 一个数量级 |
| 错误码段位冲突（D1 已用 GW-1xxx/42xx） | 低 | 中 | 本期新增 GW-4304~4306 与 D1 零冲突（roadmap §3 已 Approved 扫描） |
| 预算（spec §21.4「金额预算」）与配额（「金额维度」）职责重叠 | 中 | 中 | 边界：`Budget = 告警触发`（D2 §21.4）/ `QuotaPolicy.金额 = 硬上限触发`（D2 决策点 D-4）。同一金额值可同时配两种策略，但执行点不同 |
| `AdminMetricsController` 替换硬编码后真实数据量大导致 N+1 查询 | 低 | 中 | 一期按 tenant+date 维度聚合查询（已有 `metrics.cost` 端点 §21.6）；二期 OLAP 物化视图 |

## 工作量与阶段切分

| 阶段 | 时长 | 内容 |
|---|---|---|
| **A. 类型化与 Port**（8 任务） | 1.5 周 | `gateway-domain/billing/quota` 8 record + 2 Port + InMemory 实现 + 单测 |
| **B. 接入 Orchestrator + QuotaGate**（7 任务） | 1 周 | ChatOrchestrator 接入 recordUsage；QuotaGate 前置；既有 6 条单测零修改校验 |
| **C. BillingEngine + 预算告警**（6 任务） | 0.5 周 | 落账 + 单价快照 + 异步预算校验 + Webhook 告警 |
| **D. REST + UI**（5 任务） | 0.5 周 | AdminBilling/Quota Controller + 2 UI 页 + E2E |
| **E. 归档验证**（5 任务） | 0.5 周 | spec 核验 + verify.sh + archive/ |
| **合计** | **4 周** | 与 D 路线总览 §2.2 D2 工作量预估一致 |

## 关联文档

- D 阶段路线总览：`docs/superpowers/specs/2026-08-25-d-stage-roadmap.md` §2.2 D2 推荐主线 + §3 错误码冲突扫描
- spec 主文档：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md` §21 / §16.2 / §13.4 / §7.2 / §5.5.2
- D1 已合并产物：`openspec/changes/archive/2026-08-26-d1-iam-rbac-deepening/`（错误码段位对齐、与 RBAC 接口边界）
- 项目规范：`AGENTS.md`（多 Agent 协同 + OpenSpec 四阶段）

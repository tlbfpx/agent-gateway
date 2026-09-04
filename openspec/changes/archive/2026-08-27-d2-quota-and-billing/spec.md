# Spec: D2 多租户配额 + 成本计费（可测试需求条款）

> **状态**：📝 阶段二待评审
> **性质**：本文件是 TDD 阶段的权威需求源，每条 SHALL 必有对应测试覆盖。
> **锚点**：spec §21（成本中心与计费）/ §16.2（Quota）/ §13.4（错误码段位 GW-43xx）

## 范围与术语

### 范围

实现网关的「治理-商业化闭环」：
- 三 record 类型化（UsageRecord / CostRecord / Budget）+ 二期预留（Invoice / Quota）
- 二 Port 契约（QuotaPort / BillingPort）
- 一拦截器（QuotaGate，前置门）
- 三档超额策略（ALERT / THROTTLE / SUSPEND）
- 实时成本核算 + 单一数据源 + 预算告警
- Chargeback 报表导出（CSV/Excel 一期 CSV）
- 与 D1 RBAC 接口零破坏（既有 6 条 `AuthorizationServiceImplTest` 零修改）

### 术语澄清

| 术语 | 定义 | spec 锚点 |
|---|---|---|
| **UsageRecord** | 单次 LLM 调用的 token 用量快照（含单价快照保证可复现）| §21.2 |
| **CostRecord** | 日聚合（tenant × user × model × agent × date）的累计 token 与金额 | §21.2 |
| **Budget** | 租户级预算（daily/monthly × token/money）+ AlertThreshold 告警阈值 | §21.2 + §21.4 |
| **Quota** | 租户级配额（REQUEST / MODEL_TOKEN / MONEY 三维，ALERT/THROTTLE/SUSPEND 三档策略）| §16.2 + D2 决策点 D-1 |
| **QuotaGate** | 编排层前置拦截，封装 `QuotaPort.check()` + 三档策略动作 | D2 决策点 D-4 + 设计 §3.1 |
| **BillingPort** | 出站端口：recordUsage / queryUsage / queryCost / exportUsage | §21.6 + 本 D2 §能力 6 |
| **QuotaPort** | 出站端口：check / consume / reverse / snapshot | §16.2 + 本 D2 §能力 2 |
| **ObservabilityHooks.llm.tokens** | 单一数据源出点（spec §7.2）；D2 在此挂 `BillingPort.recordUsage` | §7.2 + §21.3 |
| **AlertThreshold** | 预算告警百分比阈值（如 80%）；超阈值且未发过则触发 | §21.4 + D2 §能力 5 |
| **AuthFilter**（D1 既有）| RBAC 评估入口；**SUSPEND 拦截留二期**（避免破坏 D1 零修改红线）| D1 GW-RBAC-006 |
| **TenantId / UserId / ModelId**（D1 既有）| 强类型 ID；D2 直接复用 `gateway-domain/shared` | D1 + D2 §复用 |

## SHALL 条款（10 条，分 3 组）

### 第 1 组：record 类型化与 Port 契约（条款 1-4）

#### `GW-QUOTA-001` UsageRecord / CostRecord / Budget 类型化
**MUST**：`gateway-domain/billing/` 定义 `UsageRecord(String recordId, TenantId, UserId, ModelId, String agentName, Instant timestamp, long tokensIn, long tokensOut, BigDecimal cost)` 与 `CostRecord`（聚合形态：tenant × user × model × agent × date 五元组 + 累计 token 与金额）与 `Budget(TenantId, UserId, BudgetType, BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal currentDailyUsed, currentMonthlyUsed, AlertThreshold alertThreshold, boolean alertSent)`。**cost 字段必须包含 `unit_price_in / unit_price_out` 两个 BigDecimal 快照字段**（spec §21.2 强约束：模型单价变更后历史账单可复现）。
- 字段上限：`Budget.dailyLimit` ≥ 0，`monthlyLimit` ≥ 0，`AlertThreshold.percent` ∈ [1, 100]。
- 类型严格对齐 spec §21.2（含 `BudgetType { TOKEN, MONEY }` 枚举）。
**测试**：单元 + 字段约束（边界值）+ 单价快照断言（cost 字段同时记录 `unit_price_in/out`）。

#### `GW-QUOTA-002` QuotaPort + BillingPort 两端口契约
**MUST**：
- `BillingPort`：`void recordUsage(UsageRecord)`、`List<UsageRecord> queryUsage(UsageQuery)`、`BigDecimal queryCost(UsageQuery)`、`List<UsageRecord> exportUsage(UsageQuery, ExportFormat)`。所有方法租户隔离（`UsageQuery` 必含 `TenantId`）。
- `QuotaPort`：`QuotaDecision check(QuotaKey)`（零参预检，不扣减）、`void consume(QuotaKey, UsageAtom)`（后置扣减，异步回写）、`void reverse(QuotaKey, UsageAtom)`（失败回滚）、`QuotaSnapshot snapshot(TenantId)`（管理后台读）。
- `QuotaKey(TenantId, ModelId, QuotaDimension)`；`QuotaDimension { REQUEST, MODEL_TOKEN, MONEY }`。
- `UsageAtom(long requests, long tokensIn, long tokensOut, BigDecimal cost)`。
- **租户隔离**：所有方法第一参数为 `TenantId`；底层 SQL/查询 `WHERE tenant_id = ?`。
**测试**：单元 + Port Contract Test（InMemory 桩跨租户隔离）。

#### `GW-QUOTA-003` QuotaDecision sealed 模式
**MUST**：`QuotaDecision` sealed interface permits `Allowed(long remaining)` / `Throttled(int newQpsPercent, Duration duration)` / `Suspended(String reason, Instant untilAt)` / `Rejected(String quotaDimension, long limit, long used)` 四个子类。Java 21 sealed 编译期强制 exhaustiveness。
- `Rejected` → HTTP 429 + `GW-4304`
- `Suspended` → HTTP 403 + `GW-4305`
- `Throttled` → HTTP 429 + `GW-4304` + 自动应用节流配置
**测试**：单测 + Pattern Matching exhaustiveness（编译期）+ HTTP 状态码映射测试。

#### `GW-QUOTA-004` QuotaPolicy 三档可配
**MUST**：`QuotaPolicy(TenantId, ModelId, QuotaDimension, QuotaAction policy, int thresholdPct, BigDecimal limitValue)`。
- `QuotaAction { ALERT, THROTTLE, SUSPEND }`。
- 同租户不同维度可独立配置。
- **SUSPEND 必须是显式管理员动作**（5 分钟冷静期，冷静期内管理员可撤销）；自动策略只到 THROTTLE。
- 字段校验：阈值 ∈ [1, 100]；`SUSPEND` 必须 `limitValue > 0`；非法 policy 取值返回 `GW-4306`。
**测试**：单测 + 字段校验 + SUSPEND 冷静期（计时器 mock）。

### 第 2 组：计费数据流与策略动作（条款 5-7）

#### `GW-QUOTA-005` 单一数据源：ObservabilityHooks → BillingPort
**MUST**：`MicrometerObservabilityHooks`（infra 实现）实现 `llm.tokens(in, out)` 回调时**异步**调 `BillingPort.recordUsage(new UsageRecord(...))`。回调链路：编排层 LLM 调用完成 → `ObservabilityHooks.llm.tokens{in,out}` → `BillingPort.recordUsage` → `InMemoryBillingRepository` 落账。
- **零主调用链阻塞**：回调用独立 executor（spec §21.3 异步 MQ 一期 InMemory 队列实现）。
- **失败容错**：回调异常 catch + log warn，不阻断 LLM 主调用链（spec §21.4 注释）。
**测试**：单测 + 集成（mock ObservabilityHooks → 验证 BillingPort.recordUsage 被调）+ 失败不阻断（回调抛异常不传播）。

#### `GW-QUOTA-006` QuotaGate 前置拦截
**MUST**：编排层（ChatOrchestrator 调用 LLM 前）调 `QuotaGate.check(tenant, model, predictedTokens)`：
1. `QuotaPort.check(QuotaKey)` 同步预检（不扣减）
2. 根据返回的 `QuotaDecision`：
   - `Allowed` → 放行，LLM 调用完成后 `consume(QuotaKey, UsageAtom)` 后置扣减
   - `Throttled` → 应用节流配置（基线 QPS 百分比 + Duration），放行（**不阻断**，仅降速）
   - `Suspended` → 抛 `QuotaExceededException("GW-4305")` → HTTP 403
   - `Rejected` → 抛 `QuotaExceededException("GW-4304")` → HTTP 429
**测试**：单测（4 种 decision 映射）+ 集成（mock QuotaPort）+ 既有 `ChatOrchestrator` 调用链兼容（spec §归档闸门 ④ 既有测试零修改）。

#### `GW-QUOTA-007` BudgetGuard 异步预算校验 + 告警触发
**MUST**：BillingEngine 落账后异步触发 `BudgetGuard.check(Budget)`：
- 累加 `currentDailyUsed / currentMonthlyUsed`
- 超过 `AlertThreshold.percent` 且 `!alertSent` → 触发告警（`RbacChangePublisher.publish(BUDGET_EXCEEDED event)`），置 `alertSent=true`
- 告警 payload 含：`tenantId / periodUsed / limit / thresholdPercent / dimension`
- **告警链路复用 D1 RbacChangePublisher 通道**（避免新基础设施）
- **失败容错**：异步任务异常 catch + log warn，**不阻断落账主流程**
**测试**：单测（mock BillingEngine + RbacChangePublisher）+ 阈值边界（80% 触发 / 90% 二次触发不重发）+ 失败不阻断。

### 第 3 组：REST 契约 + 与既有模块兼容性（条款 8-10）

#### `GW-QUOTA-008` AdminBillingController REST 端点
**MUST**：
| 方法 | 路径 | 错误码 |
|---|---|---|
| `GET` | `/v1/admin/billing/costs` | `GW-4301` 参数非法 |
| `GET` | `/v1/admin/billing/usage/export?format=CSV` | `GW-4303` 导出失败 |
| `GET / POST / PUT / DELETE` | `/v1/admin/billing/budgets` | `GW-4301 / GW-4302 / GW-4306` |

- `X-API-Key + X-Tenant-Id` header 鉴权（与 D1 AdminRolesController 一致）
- `POST /budgets` body 含 `dailyLimit / monthlyLimit / alertThreshold`；校验失败返回 `GW-4302`（日 > 月冲突）或 `GW-4306`（policy 非法）
- CSV 导出：列头 `tenant,user,model,agent,date,tokens_in,tokens_out,unit_price_in,unit_price_out,cost`，元数据快照
**测试**：集成测试 `AdminBillingControllerIT` 5+ 用例（happy + 错误码映射 + 租户隔离）。

#### `GW-QUOTA-009` `AdminMetricsController` 硬编码 1500 替换
**MUST**：删除 `AdminMetricsController.java:168` 的 `long tokens = 1500L; // 估算口径（审计无 token 字段）` 硬编码，改为调 `BillingPort.queryUsage(UsageQuery)` 真实数据。**接口契约不变**（REST 路径、响应 JSON 结构零变化），但底层数据从硬编码变为真实记账。
**测试**：回归测试 `AdminMetricsControllerIT` 断言「E2E 跑一次 LLM 调用后，AdminMetrics 显示 token 数 == LLM 实际 token 数」（不再是固定 1500）。

#### `GW-QUOTA-010` 与 D1 RBAC 接口零破坏
**MUST**：
- **不动**：`AuthorizationService` 接口签名（6 方法既有形态）、`AuthorizationServiceImpl` 公开方法、`AdminPolicyController`、`RbacFilter`、D1 `RbacCheckPoint / RbacErrorCode / RbacDecisionEvent`
- **复用**：`TenantId / UserId / ModelId / RbacChangePublisher`（D1 既有）
- **既有 6 条 `AuthorizationServiceImplTest` 零修改仍全绿**（spec §归档闸门 ④ 强约束）
**测试**：`scripts/check-rbac-backcompat.sh` 三项 PASSED（方法存在 + 0 删除行 + 6 tests 绿）。

## 错误码契约

| 错误码 | 触发场景 | HTTP 状态 | 触发位置 |
|---|---|---|---|
| `GW-4301` 账单查询参数非法 | `GET /v1/admin/billing/costs` 缺 `tenantId / from / to` | 400 | `AdminBillingController.list` |
| `GW-4302` 预算配置冲突 | `POST /budgets` 的 `dailyLimit > monthlyLimit` | 400 | `AdminBillingController.create` |
| `GW-4303` 账单导出失败 | CSV 序列化失败 / 对象存储不可用 | 500 | `AdminBillingController.export` |
| `GW-4304` 配额硬上限触发 | `QuotaDecision.Rejected`（REQUEST/MODEL_TOKEN/MONEY 任一维度） | 429 | `QuotaGate.check` |
| `GW-4305` 租户被 SUSPEND | `QuotaDecision.Suspended` | 403 | `QuotaGate.check` |
| `GW-QUOTA-error-501` 配额策略非法 | `QuotaPolicy.policy` 取值不在 `{ALERT, THROTTLE, SUSPEND}` 白名单 | 400 | `AdminBillingController.create`（注意：用 `GW-4306` 替代） |

> **更正**：上表最后一行错误码应为 **`GW-4306`**（与 proposal §错误码段分配一致）。复核 ✅。
>
> **段位零冲突自检**：
> - D1：GW-1xxx（1010~1013）+ GW-42xx（4204）
> - **D2 本期**：GW-43xx 4301~4306
> - D3：GW-5xxx
> - D4：GW-45xx/6xxx/7xxx
>
> 四主题段位完全正交，roadmap §3 已 Approved。

## 验收判定

每条 SHALL 的测试类型与最小用例数（既有 `JaCoCo ≥80%` 业务逻辑）：

| 条款 | 类型 | 用例 | 备注 | 条款 | 类型 | 用例 | 备注 |
|---|---|---|---|---|---|---|---|
| `GW-QUOTA-001` | 单元 | 6 | record 不可变 + 字段上限 + 单价快照 | `GW-QUOTA-006` | 单+集 | 4+2 | 4 decision 映射 + 既有零修改 |
| `GW-QUOTA-002` | 单元 | 6 | Port Contract + 租户隔离 | `GW-QUOTA-007` | 单元 | 4 | 阈值边界 + 失败不阻断 |
| `GW-QUOTA-003` | 单元 | 3 | sealed 编译期强制 + HTTP 映射 | `GW-QUOTA-008` | 集成 | 6 | MockMvc + 错误码映射 |
| `GW-QUOTA-004` | 单元 | 5 | 三档策略 + SUSPEND 冷静期 | `GW-QUOTA-009` | 集成 | 3 | 替换硬编码 + 真实数据断言 |
| `GW-QUOTA-005` | 集成 | 3 | ObservabilityHooks → BillingPort 链路 + 失败不阻断 | `GW-QUOTA-010` | 集成 | 1 | backcompat.sh PASSED |

**总用例数**：约 41 个新测试（10 条 SHALL 各平均 4 用例）。

**既有测试零修改证据**：6 条 `AuthorizationServiceImplTest` + 现有 D1 全部测试（`backcompat.sh` 三项检查 PASSED）。

**架构闸门**：D2 新增代码全部落入 `gateway-domain/billing / quota + gateway-application/quota + gateway-interfaces/admin/AdminBillingController`；既有 `gateway-domain/iam + gateway-domain/observability` 仅新增绑定，不改既有类。

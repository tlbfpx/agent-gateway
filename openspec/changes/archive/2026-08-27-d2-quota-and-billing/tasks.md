# Tasks: D2 多租户配额 + 成本计费（d2-quota-and-billing）

> 任务清单视图。详细 step 留待 `docs/superpowers/plans/2026-XX-XX-d2-quota-and-billing.md`（阶段三启动时由 writing-plans skill 产出）。
> 本文件给出**阶段切分**与**验收门**，作为阶段三的导航。

---

## 阶段切分

| 阶段 | 内容 | 验收门 |
|---|---|---|
| **A. 类型化与 Port**（8 任务） | `gateway-domain/billing` 8 record + `gateway-domain/quota` 2 record + `BillingPort` + `QuotaPort` + `QuotaDecision` sealed；InMemory 实现 + 单元测试 | `mvn -pl gateway-domain test` 全绿；spec 第 1 组 4 条 SHALL（GW-QUOTA-001/002/003/004）通过 |
| **B. 接入 Orchestrator + QuotaGate**（7 任务） | `MicrometerObservabilityHooks` 挂 `BillingPort.recordUsage`；`QuotaGate` + `QuotedOrchestrator` 装饰器；既有 `ChatOrchestrator` 方法签名零修改；既有 6 条 `AuthorizationServiceImplTest` 零修改红线校验 | spec 第 2 组 3 条 SHALL（GW-QUOTA-005/006/007）通过；`scripts/check-rbac-backcompat.sh` PASSED |
| **C. BillingEngine + BudgetGuard**（6 任务） | `BillingEngine` 落账 + 单价快照；`BudgetGuard` 异步校验 + 告警触发（复用 D1 `RbacChangePublisher`）；InMemory 异步队列；SUSPEND 冷静期 5 分钟 | spec 第 2 组 4 条 + GW-QUOTA-005/006/007 + 第 3 组 GW-QUOTA-008 部分通过 |
| **D. REST + UI**（7 任务） | `AdminBillingController` 4 端点；`AdminMetricsController` 替换 1500 硬编码；UI `CostCenter` + `Budgets` 2 页面 + 路由 + 侧栏；E2E 主流程 | spec 第 3 组 3 条 SHALL（GW-QUOTA-008/009/010）全绿；UI build 通过 |
| **E. 归档验证**（5 任务） | spec 10 条 SHALL 逐条核验；`mvn verify` 全模块通过；UI build + coverage；tasks.md 全部勾选；移动到 `archive/` | 完成 §6 审查清单；归档闸门 ⑫ 全勾选 |

---

## 任务列表（按阶段）

### 阶段 A：类型化与 Port（8 任务）

- [x] **A.1** 在 `gateway-domain/billing/` 新增 record `UsageRecord(String recordId, TenantId, UserId, ModelId, agentName, Instant timestamp, long tokensIn, long tokensOut, BigDecimal cost, BigDecimal unitPriceIn, BigDecimal unitPriceOut)`；canonical constructor 校验 `tokensIn/Out ≥ 0`、`cost ≥ 0`、`unitPriceIn/Out ≥ 0`
- [x] **A.2** 新增 record `CostRecord`（按日聚合五元组）+ record `Budget`（含 `QuotaAction suspendAction` + `Instant suspendUntil` 5 分钟冷静期字段）
- [x] **A.3** 新增 enum `BudgetType { TOKEN, MONEY }` + record `AlertThreshold(int percent)`（`percent ∈ [1,100]` 校验）+ record `UsageQuery(TenantId tenant, Instant from, Instant to, ModelId model, String agentName)` + record `UsageAtom(long requests, long tokensIn, long tokensOut, BigDecimal cost)`
- [x] **A.4** 新增 record `QuotaPolicy(TenantId, ModelId, QuotaDimension, QuotaAction, int thresholdPct, BigDecimal limitValue)` + enum `QuotaAction { ALERT, THROTTLE, SUSPEND }` + enum `QuotaDimension { REQUEST, MODEL_TOKEN, MONEY }` + record `QuotaKey(TenantId, ModelId, QuotaDimension)`
- [x] **A.5** 新增 sealed interface `QuotaDecision` permits `Allowed(long remaining)` / `Throttled(int newQpsPercent, Duration duration)` / `Suspended(String reason, Instant untilAt)` / `Rejected(String quotaDimension, long limit, long used)`（Java 21 sealed 编译期强制 exhaustiveness）
- [x] **A.6** 新增 Port `BillingPort`：`void recordUsage(UsageRecord)` / `List<UsageRecord> queryUsage(UsageQuery)` / `BigDecimal queryCost(UsageQuery)` / `List<UsageRecord> exportUsage(UsageQuery, ExportFormat)` + Port `QuotaPort`：`QuotaDecision check(QuotaKey)` / `void consume(QuotaKey, UsageAtom)` / `void reverse(QuotaKey, UsageAtom)` / `QuotaSnapshot snapshot(TenantId)`
- [x] **A.7** 新增 Port Contract Test（5 用例/Port × 2 Port = 10 用例）：InMemory 桩 + 租户隔离 + 4 个 QuotaDecision 分支构造
- [x] **A.8** 新增 `gateway-domain/quota/` record `Quota(TenantId, qpsLimit, dailyTokenBudget, modelSpecificLimits)`（spec §16.2 既有字段）+ InMemory `QuotaRepository`

### 阶段 B：接入 Orchestrator + QuotaGate（7 任务）

- [x] **B.1** 既有测试零修改证据基线校验：`scripts/check-rbac-backcompat.sh master` → PASSED（任务前先校验）
- [x] **B.2** 修改 `MicrometerObservabilityHooks`（infra-observability）：实现 `llm.tokens(in, out)` 回调，**异步**调 `BillingPort.recordUsage(...)`（spec §21.3 单一数据源）；失败 catch + log warn 不阻断主调用链
- [x] **B.3** 新增 `gateway-application/quota/QuotaGate`：`QuotaDecision check(tenant, model, predictedTokens)` → 4 个 decision 映射（Allowed 放行 / Throttled 应用节流 / Suspended → QuotaExceededException("GW-4305") → 403 / Rejected → QuotaExceededException("GW-4304") → 429）
- [x] **B.4** 新增 `gateway-application/quota/QuotedOrchestrator`（装饰器）：包装现有 `ChatOrchestrator`，新增 `preCheck / postConsume` 方法；**不动 ChatOrchestrator 既有方法签名**
- [x] **B.5** 新增 `InMemoryBillingRepository`（gateway-infra-persistence）：`ConcurrentHashMap<TenantId, List<UsageRecord>>` + 4 端口方法实现 + 50 线程并发测试
- [x] **B.6** 新增 `InMemoryQuotaRepository`（gateway-infra-persistence）：与既有 `RateLimiter` 共用 Redis 实例，key 前缀 `gw:quota:`；本地预扣 Caffeine + 异步回填
- [x] **B.7** B 阶段末尾再校验既有 6 条 `AuthorizationServiceImplTest` 零修改：`scripts/check-rbac-backcompat.sh master` → PASSED（spec §归档闸门 ④）

### 阶段 C：BillingEngine + BudgetGuard（6 任务）

- [x] **C.1** 新增 `gateway-application/billing/BillingEngine`：调用 `BillingPort.recordUsage` 落账 + 按日聚合 `CostRecord` + 单价快照（`unitPrice_in/out` 来自 `ModelDef.costPer1k{In,Out}`）
- [x] **C.2** 新增 `gateway-application/billing/BudgetGuard`（quarkus-style 异步任务）：`BudgetGuard.check(Budget)` 累加 `currentDaily/MonthlyUsed` → 超 `AlertThreshold.percent` 且 `!alertSent` → publish `BUDGET_EXCEEDED` 事件到 `RbacChangePublisher`（D1 通道复用）；失败 catch + log warn 不阻断
- [x] **C.3** 新增 `gateway-application/billing/UsageWriter`：InMemory `ArrayBlockingQueue` 异步分发 + 后台 drainer 线程；MQ 替换路径预留（drop-in swap，二期）
- [x] **C.4** 实现 SUSPEND 冷静期：SUSPEND 写入后 `Redis SET gw:tenant:{tenant}:suspended = true EX 300`（5 分钟）+ `ScheduledFuture` 5 分钟后落库；冷静期内管理员 DELETE 取消；故障 → 拒绝 SUSPEND（`BudgetConfigurationException`）
- [x] **C.5** 单测：`BudgetGuardTest`（阈值边界 80%/90% + 二次触发不重发 + 失败不阻断）+ `BillingEngineTest`（单价快照 + CostRecord 按日聚合）
- [x] **C.6** 集成测试：Orchestrator 装饰链完整链路（preCheck → LLM 调用 → tokens 落账 → BudgetGuard 触发）

### 阶段 D：REST + UI（7 任务）

- [x] **D.1** 新增 `AdminBillingController`（`/v1/admin/billing/`）：`GET /costs` / `GET /usage/export?format=CSV` / `GET/POST/PUT/DELETE /budgets` 4 端点；错误码 `GW-4301/4302/4303/4306`；与 D1 AdminRolesController 同款鉴权 + Deprecation 响应头模式
- [x] **D.2** 修改 `AdminMetricsController:168` 删除 `long tokens = 1500L` 硬编码，改为 `BillingPort.queryUsage(UsageQuery)` 真实数据；接口契约零变化（路径/响应结构不变）
- [x] **D.3** `agent-gateway-ui/lib/api/billing.ts`：API 封装（cost / budget CRUD / usage export），复用统一 `request.ts`
- [x] **D.4** UI `pages/CostCenter/`：实时成本看板（Tenant × Model 聚合表）+ 趋势图 + 预算状态卡片（与 D1 UserBindings 风格一致的中文后台惯例 PageHeader+Card）
- [x] **D.5** UI `pages/Budgets/`：预算阈值配置（ALERT/THROTTLE 切换 + SUSPEND 入口 + 5 分钟冷静期显示）+ 告警历史；新增路由 `/cost-center` `/budgets` + 侧栏「成本」「预算」菜单
- [x] **D.6** E2E 主流程：`RbacE2ETest` 复用（D1 已有），新增 `BillingE2ETest` 服务级（创建预算 → 触发超额 → 告警 → 取消预算 → 恢复）
- [x] **D.7** 集成测试：`AdminBillingControllerIT`（MockMvc 5 端点 × {成功/400/403/404/409}）+ `AdminMetricsControllerIT`（E2E 后真实数据断言，替换硬编码 1500）

### 阶段 E：归档验证（5 任务）

- [x] **E.1** 对照 spec.md 10 条 SHALL 逐条核验，写 `openspec/changes/d2-quota-and-billing/evidence/spec-checklist.md`
- [x] **E.2** `mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean verify -Djacoco.skip=true -q` 全绿；billing/quota 新增代码覆盖率 ≥ 80%；`scripts/check-rbac-backcompat.sh master` 仍 PASSED
- [x] **E.3** UI `npm run build` + `npm run test -- --coverage` 全绿
- [x] **E.4** 逐 Task 手工勾选 tasks.md（评审 #5 修复：避免 `sed` 一键替换）；校验脚本断言 `EXPECTED_TASKS=33` 全勾选
- [x] **E.5** `git mv openspec/changes/d2-quota-and-billing openspec/changes/archive/2026-08-26-d2-quota-and-billing/`；最终 commit 写归档说明

---

## 详细 step

详细 step 留待 `docs/superpowers/plans/2026-XX-XX-d2-quota-and-billing.md`（writing-plans skill 产出）。

---

## 关联

- proposal: `openspec/changes/d2-quota-and-billing/proposal.md`
- design: `openspec/changes/d2-quota-and-billing/design.md`
- spec: `openspec/changes/d2-quota-and-billing/spec.md`
- D 阶段路线: `docs/superpowers/specs/2026-08-25-d-stage-roadmap.md` §2.2 D2
- D1 已合并产物: `openspec/changes/archive/2026-08-26-d1-iam-rbac-deepening/`（接口边界参考 + 红线约束）

# Design: D2 多租户配额 + 成本计费（d2-quota-and-billing）

## 1. 技术决策

### 1.1 配额计数器实现（决策点 D-1）

| 项 | 选 | 理由 |
|---|---|---|
| 计数器后端 | Redis 原子计数（INCRBY + TTL） | spec §8.3 限流已用 Lua 脚本；D2 复用 Redis 实例，key 前缀 `gw:quota:*` 与 `gw:ratelimit:*` 隔离；分布式多实例共享 |
| 本地预扣 | In-process bucket（Caffeine 等） | QuotaPort 内置本地滑动窗口 + 异步回填 Redis，降低 Redis QPS 一个数量级 |
| Redis 故障降级 | 仅 ALERT，**绝不**进入 SUSPEND | spec §8.4 AOF 持久化 + 故障时降级避免误停服 |

### 1.2 计费事件流（决策点 D-2）

| 项 | 选 | 理由 |
|---|---|---|
| 分发模式 | 异步 MQ（spec §21.3 既定） | 高并发下零阻塞 LLM 主调用链 |
| 一期实现 | InMemory 队列（`ArrayBlockingQueue` + 后台 drainer 线程） | 与 D1 `NacosRbacChangePublisher` 占位策略一致；二期接 RabbitMQ/Kafka 替换 drainer |
| 失败语义 | catch + log warn，不回滚调用方（design §3.2） | spec §21.4 注释；主调用链零影响 |

### 1.3 多币种（决策点 D-3）

| 项 | 选 | 理由 |
|---|---|---|
| 一期范围 | CNY 单币种 + 字典表预留 `currency` 字段 | spec §0.1 决策 7 国内主体；字段无痛升级 |
| 二期预留 | `currency` 字段已包含在 `CostRecord` 与 `UsageRecord.cost` | 不需 schema 迁移 |

### 1.4 超额动作执行点（决策点 D-4）

| 项 | 选 | 理由 |
|---|---|---|
| 架构 | 独立 QuotaGate（先判后放） | 与 D1 RbacInflightPolicy 同架构；职责单一易测；承载 SUSPEND 租户级硬开关（spec §16.2） |
| SUSPEND 拦截落地 | 二期接入 AuthFilter | 一期仅写 `tenant.suspended` 标志位 + Redis cache；D1 既有 `AuthorizationServiceImplTest` 6 条零修改红线不可破（spec §归档闸门 ④） |
| 与 ChatOrchestrator 集成 | 新增装饰器层 `QuotedOrchestrator` | 不动 ChatOrchestrator 既有调用链（既有测试零修改） |

### 1.5 SUSPEND 冷静期（决策点 D-5）

| 项 | 选 | 理由 |
|---|---|---|
| 实现 | 5 分钟 ScheduledFuture + Redis TTL | 简单可靠；冷静期内管理员可撤销（撤回动作：DELETE `/v1/admin/billing/budgets/{id}` 的 SUSPEND 标志） |
| 自动策略禁止 SUSPEND | spec §21.4 约束 | 自动策略只到 THROTTLE；SUSPEND 必须显式管理员动作 |
| 故障熔断 | SUSPEND 写入 Redis 失败 → 拒绝 SUSPEND（抛 `BudgetConfigurationException`）| 避免假 SUSPEND |

## 2. 模块划分

### 2.1 `gateway-domain/billing/`（新增）

- **record**：`UsageRecord`、`CostRecord`、`Budget`、`Invoice`、`InvoiceLineItem`、`InvoiceStatus`（enum：`DRAFT/FINALIZED/EXPORTED/RECONCILED`）、`ExportFormat`（enum：`CSV/JSON_ADAPTER`）、`AlertThreshold`、`BudgetType`（enum：`TOKEN/MONEY`）、`UsageQuery`、`UsageAtom`
- **Port**：`BillingPort`（`recordUsage / queryUsage / queryCost / exportUsage`）、`QuotaPort`（`check / consume / reverse / snapshot`）
- **Domain Service**：`BillingEngine`（落账 + 单价快照）、`BudgetGuard`（异步预算校验 + 告警触发）、`UsageWriter`（异步回写 + 异常吞咽）
- **Sealed**：`QuotaDecision` permits `Allowed/Throttled/Suspended/Rejected`；`QuotaAction`（enum：`ALERT/THROTTLE/SUSPEND`）

### 2.2 `gateway-domain/quota/`（新增）

- **record**：`Quota`(spec §16.2 既有)、`QuotaPolicy`、`QuotaKey`、`UsageAtom`
- **Sealed**：复用 `QuotaDecision`（放在 billing 包，避免跨包循环）

### 2.3 `gateway-application/`（新增拦截层）

- **`QuotaGate`**（Spring `@Component`）：编排层前置拦截，封装 `QuotaPort.check()` + 决策映射
- **`QuotedOrchestrator`**（装饰器）：包装现有 `ChatOrchestrator`，新增 `preCheck()` 与 `postConsume()` 方法；**不动 ChatOrchestrator 既有方法签名**

### 2.4 `gateway-infra-persistence/`（新增实现）

- **`InMemoryBillingRepository`**（一期默认）：`ConcurrentHashMap<TenantId, List<UsageRecord>>`；`CostRecord` 按日聚合
- **`InMemoryQuotaRepository`**（一期默认）：与 `RateLimiter` 共用 Redis 实例，key 前缀 `gw:quota:`；本地预扣 `Caffeine`
- 既有 `InMemoryCostRepository` 废弃（被 `InMemoryBillingRepository` 替代；spec §21.2 演进）

### 2.5 `gateway-interfaces/`（新增 REST）

- **`AdminBillingController`**（`/v1/admin/billing/`）：costs / usage/export / budgets 4 端点
- **`AdminQuotaController`**（`/v1/admin/billing/budgets`）：预算 CRUD（与 AdminBillingController 可合并，本 D2 拆为两个 controller 便于权限边界）

### 2.6 `agent-gateway-ui/`（新增页面）

- `pages/CostCenter/`：实时成本看板 + 趋势图 + 预算状态
- `pages/Budgets/`：预算阈值配置（SUSPEND 入口）+ 告警历史

## 3. 数据模型

### 3.1 record 定义（spec §21.2 + 本 D2 扩展）

```java
// gateway-domain/billing/UsageRecord.java
public record UsageRecord(
    String recordId, TenantId tenant, UserId user, ModelId model,
    String agentName, Instant timestamp,
    long tokensIn, long tokensOut,
    BigDecimal cost,                    // 已计算金额
    BigDecimal unitPriceIn,            // 单价快照（spec §21.2 强约束）
    BigDecimal unitPriceOut) {}

// gateway-domain/billing/CostRecord.java（按日聚合）
public record CostRecord(
    String id, TenantId tenant, UserId user, ModelId model,
    String agentName, LocalDate date,
    long totalTokensIn, long totalTokensOut,
    BigDecimal totalCost, String currency) {}

// gateway-domain/billing/Budget.java
public record Budget(
    TenantId tenant, UserId user, BudgetType type,
    BigDecimal dailyLimit, BigDecimal monthlyLimit,
    BigDecimal currentDailyUsed, BigDecimal currentMonthlyUsed,
    AlertThreshold alertThreshold, boolean alertSent,
    QuotaAction suspendAction,         // D2 新增：一期 SUSPEND 标志位
    Instant suspendUntil) {            // 5 分钟冷静期

    public enum BudgetType { TOKEN, MONEY }
}

// gateway-domain/billing/QuotaPolicy.java
public record QuotaPolicy(
    TenantId tenant, ModelId model, QuotaDimension dimension,
    QuotaAction policy, int thresholdPct, BigDecimal limitValue) {
    public enum QuotaAction { ALERT, THROTTLE, SUSPEND }
    public enum QuotaDimension { REQUEST, MODEL_TOKEN, MONEY }
}
```

### 3.2 表结构（Flyway SQL 草案，存 `openspec/changes/d2-quota-and-billing/sql/`）

```sql
-- 配额计数器（Redis 主，PG 异步镜像）
CREATE TABLE quota_counters (
    tenant_id    BIGINT NOT NULL,
    model_id     VARCHAR(64) NOT NULL,
    dimension    VARCHAR(16) NOT NULL,   -- REQUEST / MODEL_TOKEN / MONEY
    period       DATE NOT NULL,            -- 自然日，按日清
    used_value   BIGINT NOT NULL DEFAULT 0,
    policy       VARCHAR(16) NOT NULL,    -- ALERT / THROTTLE / SUSPEND
    threshold_pct INT NOT NULL DEFAULT 80,
    updated_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, model_id, dimension, period)
);

-- 计费明细（spec §21.2 usage_record + 单价快照）
CREATE TABLE billing_records (
    record_id      VARCHAR(40) PRIMARY KEY,
    tenant_id      BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    model_id       VARCHAR(64) NOT NULL,
    agent_name     VARCHAR(128),
    ts             TIMESTAMP NOT NULL,
    tokens_in      BIGINT NOT NULL,
    tokens_out     BIGINT NOT NULL,
    unit_price_in  DECIMAL(18,8) NOT NULL,  -- 单价快照
    unit_price_out DECIMAL(18,8) NOT NULL,
    cost           DECIMAL(18,6) NOT NULL,
    currency       CHAR(3) NOT NULL DEFAULT 'CNY',
    INDEX idx_tenant_model_day (tenant_id, model_id, ts)
);

-- 周期账单导出（spec §21.5 chargeback）
CREATE TABLE invoice_exports (
    invoice_id     VARCHAR(40) PRIMARY KEY,
    tenant_id      BIGINT NOT NULL,
    period         CHAR(7) NOT NULL,       -- YYYY-MM
    status         VARCHAR(16) NOT NULL,   -- DRAFT / FINALIZED / EXPORTED
    total_cost     DECIMAL(18,6) NOT NULL,
    currency       CHAR(3) NOT NULL DEFAULT 'CNY',
    storage_uri    VARCHAR(512),
    format         VARCHAR(16) NOT NULL,   -- CSV / JSON_ADAPTER
    generated_at   TIMESTAMP NOT NULL,
    exported_at    TIMESTAMP,
    INDEX idx_tenant_period (tenant_id, period)
);
```

## 4. 关键交互流

### 4.1 LLM 调用 → 单一数据源（spec §21.3）

```
ChatOrchestrator.executeStep
   ↓
QuotedOrchestrator.preCheck(tenant, model, predictedTokens) ← D2 装饰器
   ↓
QuotaGate.check() → QuotaPort.check(QuotaKey)
   ↓ 返回 QuotaDecision
   ├─ Allowed     → 放行 + ChatOrchestrator 既有调用链
   ├─ Throttled   → 应用节流 + 放行
   ├─ Suspended   → throw QuotaExceededException("GW-4305") → HTTP 403
   └─ Rejected    → throw QuotaExceededException("GW-4304") → HTTP 429
   ↓
ChatOrchestrator（既有逻辑，未修改）→ LLM 调用完成
   ↓
ObservabilityHooks.llm.tokens(in, out)        ← spec §7.2 钩子
   ↓
MicrometerObservabilityHooks（infra 实现）异步分发
   ↓
BillingPort.recordUsage(new UsageRecord(...))  ← D2 挂接点
   ↓
InMemoryBillingRepository 落账
   ↓
BillingEngine 累加 CostRecord（按日聚合）
   ↓
BudgetGuard.check(Budget) 异步触发
   ├─ 超阈值 → publish BUDGET_EXCEEDED → D1 RbacChangePublisher
   └─ 失败容错 catch + log warn，不阻断
   ↓
QuotedOrchestrator.postConsume(tenant, model, realTokens) ← D2 后置扣减（可选）
   ↓
QuotaPort.consume(QuotaKey, UsageAtom)
```

### 4.2 超额 SUSPEND 流程（一期仅写标志位，二期接入 AuthFilter）

```
管理员 POST /v1/admin/billing/budgets（含 QuotaAction.SUSPEND）
   ↓
AdminBillingController 校验（GW-4306 非法 policy）
   ↓
BillingPort.saveBudget(Budget with suspendAction=SUSPEND)
   ↓ 5 分钟冷静期
Redis SET gw:tenant:{tenant}:suspended = true EX 300
   ↓
本地 ScheduledFuture 5 分钟后真正落库（持久化 suspended=true）
   ↓（冷静期内管理员可 DELETE 取消）
   ↓
[二期] AuthFilter 集成：suspended=true → return 403（不在本 D2）
```

### 4.3 预算告警链路（spec §21.4 + D1 RbacChangePublisher 复用）

```
BillingEngine.recordUsage → BudgetGuard.check（异步）
   ↓
if (currentUsed > limit × thresholdPct/100 && !alertSent) {
   publish(BUDGET_EXCEEDED event{tenantId, periodUsed, limit, thresholdPercent, dimension})
   ↓
D1 RbacChangePublisher 投递
   ↓（复用 D1 WebhookEventBridge 通道）
WebhookEventBridge → webhook URL（邮件 + Webhook）
   ↓
Budget.alertSent = true（持久化）
```

## 5. 风险与缓解（design 维度 · 与 proposal §风险、roadmap §4.2 协同）

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Redis 故障导致配额计数漂移 | 中 | 高（超额不被拦截） | §8.4 已要求 Redis AOF；故障时降级为"仅 ALERT"，**绝不进入 SUSPEND**（避免误停服） |
| 异步 MQ 积压导致报表延迟 | 中 | 中 | 队列长度指标 `billing.queue.lag`；超阈值告警；OLAP 主库保留 90 天可重放 |
| SUSPEND 误触发导致全租户停服 | 低 | 极高 | SUSPEND 冷静期 5 分钟（spec §21.4 + D2 §能力 4）；自动策略只到 THROTTLE |
| 模型单价变更导致历史账单口径漂移 | 中 | 中 | `billing_records` 落库时快照 `unit_price_in / unit_price_out`，账单重算可复现（spec §21.2 强约束） |
| 配额检查本身成为热点 | 低 | 中 | QuotaPort 内置本地预扣（in-process bucket）+ 异步回填 Redis，降低 Redis QPS 一个数量级 |
| D1 既有测试零修改红线被破 | 低 | 高 | QuotaGate 独立于 AuthorizationService 决策路径；D2 §能力 8 不动 `AuthorizationService` 接口签名；既有 6 条 `AuthorizationServiceImplTest` 零修改 |
| 错误码段位冲突 | 低 | 中 | spec §13.4 已规划 GW-43xx 4301~4303；D2 新增 4304~4306 与 D1 GW-1xxx/42xx 零冲突（roadmap §3 已 Approved 扫描） |
| 预算（告警）与配额（硬上限）职责重叠 | 中 | 中 | 边界：`Budget = 告警触发`（D2 §21.4）/ `QuotaPolicy.金额 = 硬上限触发`（D2 决策点 D-4）。同一金额值可同时配两种策略，但执行点不同 |
| `AdminMetricsController` 替换硬编码后真实数据量大导致 N+1 查询 | 低 | 中 | 一期按 tenant+date 维度聚合查询；二期 OLAP 物化视图 |
| 新模块依赖破坏既有 ChatOrchestrator 测试 | 中 | 高 | QuotedOrchestrator 装饰器模式 + ChatOrchestrator 既有方法零修改；E2E 测试验证 D1+D2 链路兼容 |

## 6. 占位清单（plan §评审 #8 已采纳）

| 占位项 | 任务 | 占位范围 | 二期清理 |
|---|---|---|---|
| `NacosRbacChangePublisher`（D1） | B.3 | 一期：Flow.Subscriber + log.warn 占位；二期接 nacos-client | D2 复用通道 + D 阶段收尾 PR：删除 log 占位 + 注入 NacosConfigService |
| `AdminPolicyController` 旧 `/policies` 端点（D1） | B.8 | 一期保留 + Deprecation header；二期清理 | `d2-followup-cleanup` change：删除 controller + 旧测试 |
| `AuthorizationServiceImpl` 双形态构造器（D1） | B.5 | 一期：零参 + 双参；二期：零参废弃 | 二期：零参删除；既有测试需改双参构造 |
| `AuthorizationService` 接口 6 方法（D1） | A.15 | 一期 4 原方法 + 2 checkPoint 重载 | 二期：考虑把 4 原方法 default delegate 到 2 重载（让接口收敛到 2 方法） |
| QuotaGate + InMemoryBillingRepo（D2） | A.5 / C.2 | 一期：InMemory 占位；二期：JPA + Redis | 独立 `d2-jpa-implementation` change |
| `QuotedOrchestrator` 装饰器（D2） | B.2 | 一期：ChatOrchestrator 调用链装饰；二期：可下沉到 ChatOrchestrator 内 | `d2-orchestrator-refactor` change |

## 7. 与 D1/D3/D4 接口边界

| 接口 | 来源 | D2 是否依赖 | 复用方式 |
|---|---|---|---|
| `TenantId / UserId / ModelId` | D1（既有 shared） | ✅ | 直接 import |
| `RbacChangePublisher` | D1（既有） | ✅ | 预算告警事件复用 |
| `WebhookEventBridge` | D1/C（既有） | ✅ | 告警投递复用通道 |
| `ObservabilityHooks` | 既有 | ✅ | 在 `MicrometerObservabilityHooks` 挂 `BillingPort.recordUsage` |
| `ModelDef.costPer1kIn/Out` | 既有 spec §5.5.2 | ✅ | `BillingEngine` 查表换算 |
| `AuthorizationService` | D1 既有 | ❌（不依赖） | D2 独立 QuotaGate，不动 RBAC 决策路径 |
| D3 RAG | 未开始 | ❌ | 无接口交互 |
| D4 IM/Webhook | 未开始 | ✅（WebhookEventBridge） | 同 D1 |

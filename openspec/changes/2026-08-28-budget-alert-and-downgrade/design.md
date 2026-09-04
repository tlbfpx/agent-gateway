# Design: 预算告警接线 + 超限降级（budget-alert-and-downgrade）

## 1. 两级告警与去重

| 项 | 选 | 理由 |
|---|---|---|
| 告警级别 | 阈值（默认 80%）warning + 100% critical | 与任务要求对齐；阈值来自 `Budget.alertThreshold` 可配 |
| 存储 | `AlertStore`（domain 端口，Pg/内存实现） | 复用 2026-08-19 告警体系，运营台告警流直接可见 |
| 去重键 | `budget:{tenant}:{pct}`，存在 firing 记录即跳过 | 每级各去重一次，避免每笔用量重复告警；与 AlertEngine 的 dedup_key 语义一致 |
| Webhook | `GatewayEvents.publish("budget.alert", ...)` | application 层不依赖 interfaces 的 WebhookDispatcher；经既有 `WebhookEventBridge` 桥接，HMAC 签名/重试/死信全复用 |
| 容错 | 全链路 catch + log warn | GW-QUOTA-007：告警链路异常不阻断计费落账 |

原有 `RbacChangePublisher`（BUDGET_EXCEEDED 借道 ROLE_UPSERT）通道保留不变，向后兼容。

## 2. 超限降级

| 项 | 选 | 理由 |
|---|---|---|
| 字段位置 | `Budget` record 追加 `overLimitAction` + `fallbackModel` | 预算是租户级策略的单一事实源 |
| 默认值 | null → BLOCK（compact constructor 规范化） | 现状 429 语义零变化 |
| 兼容 | 保留旧 11 参构造器委托新构造器 | 13 处存量 `new Budget(` 调用点零改动 |
| 判定逻辑 | `BudgetDowngradePolicy.downgradeModelFor(tenant, requested)` | 独立类便于单测与复用；仅 DOWNGRADE + fallbackModel 非空 + requested ≠ fallback 时返回目标 |
| 接入点 | `ChatOrchestrator` token 预算扣减失败分支 | 仅追加分支：可降级 → 使用 fallbackModel（仍过 `checkUseModel` RBAC，发 `budget.downgrade` 事件）；不可降级 → 维持原 429。setter `setBudgetDowngradePolicy` 注入，构造器/既有逻辑不动 |
| 查询失败 | 返回 empty（按 BLOCK） | 降级是优化路径，不放大 BudgetRepository 故障 |

## 3. 持久化与 API

- `budgets` 表加列 `over_limit_action VARCHAR(16) NOT NULL DEFAULT 'BLOCK'`、`fallback_model VARCHAR(128)`；`PgBillingRbacSchemaInitializer` 幂等执行（`ADD COLUMN IF NOT EXISTS` 存量库安全）。
- `PgBudgetRepository.mapRow`：BLOCK 时强制 fallbackModel=null（防脏数据）；DOWNGRADE 校验由 Budget 构造器兜底。
- `POST/PUT /v1/admin/billing/budgets`：`overLimitAction` 非 `{BLOCK, DOWNGRADE}` → 400 GW-4306；DOWNGRADE 缺 fallbackModel → 400 GW-4306。旧请求体（无两字段）兼容。

## 4. 装配

- `BillingQuotaAutoConfiguration`：BudgetGuard 可选注入 AlertStore/GatewayEvents（`@Autowired(required=false)`，缺失退化原行为）；注册 `BudgetDowngradePolicy` bean。
- `OrchestrationConfig`：可选注入 policy → `orchestrator.setBudgetDowngradePolicy(...)`。

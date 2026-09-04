# D2 Chunk 1 验收（spec 第 1 组 4 条 SHALL）

## 测试增量（43 个新单元测试）

- UsageRecordTest：6 用例（recordId/tokens/cost/unitPrice 校验 + 字段全集 + equals）
- CostRecordTest：4 用例（tokens 非负 / currency 校验 / CNY 默认 / 字段全集）
- BudgetTest：6 用例（AlertThreshold 范围 / tenant 非空 / limit 非负 / SUSPEND↔suspendUntil 互约束 ×2 / 字段全集）
- QuotaDecisionTest：5 用例（4 record 字段 + sealed Pattern Matching 编译期强制）
- BillingPortContractTest：6 用例（record+query / 租户隔离 / 多维过滤 / 成本求和 / 导出 / InMemory 实现同契约）
- QuotaPortContractTest：6 用例（Allowed/Rejected/consume+check/reverse/snapshot/租户隔离）
- BudgetRepositoryContractTest：5 用例（save+find / 租户隔离 / markAlertSent 幂等 / accumulateUsage / delete）
- BillingExceptionsTest：5 用例（异常构造 + cause 链 + BillingErrorCode 6 常量对齐 spec + 唯一性）

## spec SHALL 达成

- ✅ `GW-QUOTA-001` UsageRecord / CostRecord / Budget 类型化 + 单价快照
- ✅ `GW-QUOTA-002` BillingPort / QuotaPort / BudgetRepository 契约 + 租户隔离 + InMemory 实现
- ✅ `GW-QUOTA-003` QuotaDecision sealed Pattern Matching exhaustiveness
- ✅ `GW-QUOTA-004` QuotaPolicy 三档（ALERT/THROTTLE/SUSPEND）+ SUSPEND 冷静期约束

## 错误码段自检

- ✅ D2 本期使用 GW-43xx 段（4301~4306 共 6 码）
- ✅ 与 D1 GW-1xxx/42xx 零冲突
- ✅ 与 D3 GW-5xxx、D4 GW-45xx/6xxx/7xxx 零冲突（roadmap §3 已 Approved 扫描）

## 全量回归

- gateway-domain：165 tests 全绿（D1 既有 122 + D2 新增 43），零回归

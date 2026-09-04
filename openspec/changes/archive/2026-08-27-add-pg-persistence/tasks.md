# Tasks: PG 持久化补全

- [x] **A.1** schema-billing-rbac.sql（5 表幂等 DDL）+ PgBillingRbacSchemaInitializer
- [x] **A.2** PgBillingRepository 实现 BillingPort（recordUsage/queryUsage/queryCost/exportUsage + 单价快照）
- [x] **A.3** PgBudgetRepository 实现 BudgetRepository（upsert/原子累加/markAlertSent 幂等）
- [x] **A.4** PgQuotaRepository 实现 QuotaPort（日键 UPSERT 计数，check/consume/reverse/snapshot）
- [x] **A.5** PgRoleRepository + PgRoleBindingRepository（sealed Permission JSON 三型映射）
- [x] **A.6** InfraPersistenceAutoConfiguration 装配（beforeName 排序 + ConditionalOnMissingBean 降级保留）
- [x] **A.7** application.yml 开启 observability.storage.jdbc-url（localhost:5433 compose PG）
- [x] **B.1** PgBillingRbacStoresIT（Testcontainers，重启等价断言）跑绿（-Pit）
- [x] **B.2** 全量回归 mvn test + backcompat.sh PASSED
- [x] **B.3** 真实重启验证：建预算/落账 → kill 网关 → 重启 → 数据仍在
- [x] **B.4** e2e 回归（7 用例）全绿
- [x] **B.5** 归档

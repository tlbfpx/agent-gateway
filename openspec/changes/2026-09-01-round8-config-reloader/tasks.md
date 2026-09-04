# Tasks: 热配置重载（round8-config-reloader）

- [x] **A.1** 领域接口：ConfigChanged / ConfigReloadBus / InMemoryConfigReloadBus
- [x] **A.2** 基础设施增强：ConfigFileWatcher / ConfigSourceRegistry / ConfigSourceAutoConfiguration
- [x] **A.3** 多源支持：NacosConfigSubscriber / K8sConfigMapWatcher（条件激活）
- [x] **A.4** JSON 持久化归并：JsonFileRoleStore / JsonFileWebhookStore（统一到 infra-config）
- [x] **A.5** 状态端点：ConfigStatusController（/actuator/config-status）
- [x] **A.6** Web API：AdminConfigHistoryController（/v1/admin/config/history）
- [x] **B.1** UI：pages/ConfigReloader.tsx + lib/api/configStatus.ts + Sidebar / Routes 注册
- [x] **C.1** 单元测试：domain/config + infra-config 全过
- [x] **C.2** 集成验证：mvn -pl gateway-domain,gateway-infra-config -am test BUILD SUCCESS
- [x] **D.1** 文档：openspec 变更记录（proposal / design / spec / tasks）

## 验收门禁

- [x] 后端单测：domain/config + infra-config 测试套件全过
- [x] 单模块 verify：`mvn -pl gateway-domain,gateway-infra-config -am test` BUILD SUCCESS
- [x] Spec 条款 GW-CONF-001 ~ GW-CONF-010 全部覆盖
- [x] Round 8 commit `509cf8f0` 落地
- [x] Round 8.5 fix `a9769bd8`（Spring 4.0 严格模式）落地，verify.sh 11 模块全绿
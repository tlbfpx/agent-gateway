# Spec: 热配置重载（可测试条款）

#### GW-CONF-001 Pub/Sub 抽象
**MUST**：`ConfigReloadBus` 接口提供 `publish(ConfigChanged)` + `subscribe(String keyPattern, Consumer<ConfigChanged>)`；InMemoryConfigReloadBus 单进程实现，subscribe 立即触发 publish 的事件（无 backlog）。
**测试**：InMemoryConfigReloadBusTest.subscribeReceivesPublishedEvent / patternMatching。

#### GW-CONF-002 文件监听
**MUST**：`ConfigFileWatcher` 用 JDK WatchService 监听配置目录变更（递归子目录），200ms debounce 内只触发一次；新增/修改/删除文件都触发 `publish`。
**测试**：ConfigFileWatcherTest.modifyTriggersEvent / deleteTriggersEvent / debounceCoalescesMultipleEvents。

#### GW-CONF-003 多源聚合
**MUST**：`ConfigSourceRegistry` 启动时根据配置激活本地文件 / Nacos / K8s 三个 source；每个 source 的变更统一发布到 bus。
**测试**：ConfigSourceRegistryTest.activatesEnabledSources / deactivatesDisabledSources。

#### GW-CONF-004 配置 reload 不重启
**MUST**：AdminApiKeyController / AdminWebhookController / NacosModelRegistry 订阅 bus 后，收到对应 prefix 的事件**只 reload 受影响的数据**（不重启 Spring context，不重建 bean）。
**测试**：AdminApiKeyControllerTest.reloadUpdatesInMemoryStore / NacosModelRegistryTest.reloadUpdatesRoutes。

#### GW-CONF-005 JSON 持久化兼容
**MUST**：`JsonFileRoleStore` / `JsonFileWebhookStore` 读写已有 JSON 文件向后兼容；缺失字段用默认值；schema 迁移在启动时自动执行。
**测试**：JsonFileRoleStoreTest.readLegacyJson / JsonFileWebhookStoreTest.writeRoundtrip。

#### GW-CONF-006 Schema 校验失败阻塞启动
**MUST**：启动时校验所有 JSON 配置文件 schema，校验失败 → Spring context 启动失败（明确错误日志）。
**测试**：ConfigSourceAutoConfigurationTest.invalidSchemaBlocksStartup。

#### GW-CONF-007 ConfigStatus 端点
**MUST**：`GET /actuator/config-status` 返回当前 source 列表、最近 N 条变更（含 timestamp / source / changedKeys / 触发人）。
**测试**：ConfigStatusControllerTest.returnsSourceList / returnsRecentChanges。

#### GW-CONF-008 ConfigHistory 端点
**MUST**：`GET /v1/admin/config/history?limit=20` 列出最近 N 条配置变更（与 ConfigStatusController 协同）。
**测试**：AdminConfigHistoryControllerTest.returnsRecentHistory。

#### GW-CONF-009 手动 reload
**MUST**：`POST /v1/admin/config/reload` 立即触发全部 source 重读并 publish 事件；运营应急用。
**测试**：ConfigStatusControllerTest.manualReloadPublishesAllSources。

#### GW-CONF-010 订阅粒度
**MUST**：subscribe 的 keyPattern 支持前缀匹配（`*` 通配，如 `webhook.*`）；非匹配事件不触发订阅者。
**测试**：InMemoryConfigReloadBusTest.patternMatching / nonMatchingNotDelivered。
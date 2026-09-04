# Design: 热配置重载

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| 触发源 | 本地文件 (WatchService) + Nacos + K8s ConfigMap | 覆盖主流部署形态；本地开发友好 |
| 事件总线 | 单进程 in-memory pub/sub（domain port 抽象） | 单实例场景足够；多实例未来可接 Kafka/Redis |
| 订阅粒度 | 按 config key 前缀订阅（如 `webhook.*`、`provider.openai.*`） | 只 reload 受影响 bean，避免全量重建 |
| 防抖 | 文件 watcher 内置 200ms debounce | 防编辑器多次保存触发抖动 |
| 持久化 | JsonFileRoleStore / JsonFileWebhookStore（每个 config 一文件） | 可读、可 diff、可手工编辑 |
| Schema 校验 | 启动时 JSON Schema 校验，失败启动阻塞 | 防止坏配置上线 |

## 2. 数据流

```
外部配置源变更
  ├─ 本地文件 → ConfigFileWatcher (WatchService) → 200ms debounce → bus.publish(changedKeys)
  ├─ Nacos → NacosConfigSubscriber (long-poll) → bus.publish(changedKeys)
  └─ K8s → K8sConfigMapWatcher (informer) → bus.publish(changedKeys)
  ↓
InMemoryConfigReloadBus
  ↓ (按 prefix 匹配订阅)
  ├─ AdminApiKeyController.subscription("apikey.*") → reloadStore()
  ├─ AdminWebhookController.subscription("webhook.*") → reloadStore()
  └─ NacosModelRegistry.subscription("provider.*") → reloadModels()
  ↓
ConfigStatusController (audit log + 最近 N 条历史)
```

## 3. 配置

```yaml
gateway:
  config-reloader:
    enabled: ${CONFIG_RELOADER_ENABLED:true}
    sources:
      file:  # 默认
        path: ${CONFIG_DIR:./data}
        debounce-ms: 200
      nacos:  # 可选
        server-addr: ${NACOS_ADDR:}
        namespace: ${NACOS_NAMESPACE:}
      k8s:    # 可选
        in-cluster: true
        config-map: agent-gateway-config
```

## 4. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 配置变更竞态（reload 中又有新变更） | reload 串行化（per-key 锁），新变更入队等当前完成 |
| 误改配置导致行为异常 | 启动时 JSON Schema 校验；运营可手动禁用 config-reloader |
| 重启后配置丢失 | 持久化到 JSON 文件；Nacos / K8s ConfigMap 自带持久化 |
| 多实例不一致 | 未来 Round 9 加 Redis pub/sub 做跨实例广播 |

## 5. 涉及文件

| 模块 | 文件 |
|---|---|
| gateway-domain/config | ConfigChanged / ConfigReloadBus / InMemoryConfigReloadBus |
| gateway-domain/test/config | InMemoryConfigReloadBusTest |
| gateway-infra-config（增强） | ConfigFileWatcher / ConfigSourceAutoConfiguration / ConfigSourceRegistry / NacosConfigSubscriber / K8sConfigMapWatcher / JsonFileRoleStore / JsonFileWebhookStore / ConfigStatusController |
| gateway-interfaces | AdminConfigHistoryController（已修改） |
| agent-gateway-ui | pages/ConfigReloader.tsx / lib/api/configStatus.ts / Sidebar / Routes |
# Proposal: 热配置重载（round8-config-reloader）

> **状态**：实现已完成（commit `509cf8f0`），本变更记录为事后补档
> **来源**：Round 8 候选 — 运营改配置后必须重启进程是高频痛点

## 动机

运营台目前大量配置项（API Key、Webhook、RBAC、Provider、Rate Limit、模型路由等）改完后**需要重启 gateway 进程才能生效**。在生产环境这是高频痛点：
- Webhook URL 改一下要等下一次发版窗口
- Provider 切流量要 5-10 分钟窗口期
- 紧急关闭某个 Key 要重启整个进程

本变更引入**配置热加载**：监听配置源变更事件，自动让相关 bean 重新加载；运营台改完即时生效。

## What

### 领域接口（gateway-domain/config）
- `ConfigChanged`：配置变更事件（payload + source + timestamp + changedKeys）
- `ConfigReloadBus`：订阅/发布抽象端口（domain 层接口）
- `InMemoryConfigReloadBus`：单进程实现（默认）

### 基础设施（gateway-infra-config 增强 + 新文件）
- `ConfigFileWatcher`：JDK WatchService 监听本地 JSON 文件变更
- `ConfigSourceRegistry`：多源聚合（本地文件 / Nacos / K8s ConfigMap）
- `NacosConfigSubscriber`：从 Nacos 订阅配置变更
- `K8sConfigMapWatcher`：从 K8s API Server 监听 ConfigMap 变更
- `JsonFileRoleStore` / `JsonFileWebhookStore`：角色 / Webhook JSON 文件持久化（此前零散，本变更统一归并）
- `ConfigSourceAutoConfiguration`：Spring Boot 自动装配
- `ConfigStatusController`：`/actuator/config-status` 状态端点

### 集成
- 各 Admin 控制器（API Key / Webhook / Model / Role）订阅 `ConfigReloadBus`，收到事件后**只 reload 受影响的部分**（不重启进程）
- `AdminConfigHistoryController`：列出最近 N 条 config 变更（与 infra-config 的 ConfigStatusController 协同）

### UI
- `/config-reloader` 路由（Sidebar "配置重载" 入口）
- `pages/ConfigReloader.tsx`：配置源状态/最近变更时间/手动 reload 按钮
- `lib/api/configStatus.ts`：前端 SDK

## Non-goals

- 不做配置版本控制（Round 9 待办；当前仅记录变更时间 + 来源）
- 不做配置回滚（运营手动 revert JSON 文件即可）
- 不做配置差异对比（人工 review 文件即可）
- 不替换 JsonFileRoleStore 的存储格式（向后兼容已有 JSON 文件）

## 验收

- 后端：domain/config + infra-config 测试全过
- 集成：JSON 文件变更 → 30 秒内 ConfigReloadBus 触发 → 相关 bean reload
- UI：ConfigReloader 页面渲染配置源状态 + 最近变更列表
- 配置：`gateway.config-reloader.enabled=true`（默认 true，开发模式自动启用）
- 测试覆盖：文件监听、bus pub/sub、多源聚合、JSON 持久化
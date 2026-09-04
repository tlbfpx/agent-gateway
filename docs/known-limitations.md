# 已知限制（Known Limitations）

> 最后更新：2026-09-04 · 维护约定：每轮优化若引入或消除限制，同步更新本文件
>
> 本文件记录**已明确决定不做 / 暂缓**的能力缺口，避免后续接手时把「演示可用」误判成「生产就绪」。
> 与各轮报告 §已知限制 的区别：报告里的是「留给下一轮」，这里的是「已决定放弃或长期搁置」。

---

## 1. Dataset / K8sGateway / Mcp 三处仍为内存态存储

**状态**：已放弃原 Round 16 #2 计划（该任务于 2026-09-03 删除），无后续排期。

**影响面**

| 端口 / 服务 | 实现类 | 落盘 | 重启后果 |
|---|---|---|---|
| 评测数据集 / case / run | `InMemoryDatasetRepositories`（`gateway-infra-persistence/.../dataset/`） | ❌ | 数据集、用例、评测历史全部丢失 |
| `K8sGatewayPort` | `InMemoryK8sGatewayStore`（`.../k8s/`） | ❌ | 已注册的网关路由配置丢失 |
| `McpPort` | `InMemoryMcpServerRepository`（`.../mcp/`） | ❌ | MCP Server 注册表丢失（内置种子数据会重建） |

**因此**

- 这三块功能定位为**单实例演示 / 开发自测**，不具备多副本部署能力（各副本内存互不可见）。
- 评测报表不可作为长期质量基线——重启即归零，无法做跨版本趋势对比。
- 前端 `/datasets`、K8s、MCP 相关页面在生产环境会表现为「数据莫名消失」，属预期行为而非 bug。

**若将来要补 Pg，注意两个额外工作量（不是加个 PgXxxRepository 就完事）**

1. **Dataset 缺端口抽象**：`gateway-domain/.../dataset/` 下只有实体（`EvalDataset` / `EvalCase` / `EvalRun`）和 `Judge`，**没有 Repository 接口**。
   `DatasetService`、`EvalRunService` 直接依赖具体类 `InMemoryDatasetRepositories`，
   所以切 Pg 需先抽端口接口 + 改应用层构造签名，属破坏性重构。
   对比：K8s/Mcp 已有 `K8sGatewayPort` / `McpPort` 端口，替换成本低得多。
2. **Bean 装配是 `@ConditionalOnMissingBean(具体类)`**（见 `DatasetAutoConfiguration` / `K8sAutoConfiguration` / `McpAutoConfiguration`），
   新增 Pg 实现**不会**自动顶掉 InMemory bean，需要同步改自动配置类的条件。

**已落 Pg 的对照**（8 个规划中的 Repo 覆盖 5 个，62%）：Feedback、AdminUser、Team、PromptTemplate、PromptVersion ✅

---

## 2. 插件运行时保持 Java SPI，无 Wasm 级隔离

**状态**：已放弃原 Round 16 #3（Chicory Wasm 集成）计划（该任务于 2026-09-03 删除）。
现有 Java SPI 架构、4 个官方内置插件、`/v1/admin/plugins` 五个端点均**不受影响**，功能可用。

**隔离能力的真实边界** —— `PluginSandbox`（`gateway-application/.../plugin/PluginSandbox.java`）名为沙箱，实际只提供两项保护：

| 保护 | 是否具备 | 说明 |
|---|---|---|
| 异常隔离 | ✅ | 每个插件独立 try/catch，抛异常仅记 warn 并跳过，不阻断整链 |
| 短路控制 | ✅ | 任一插件 `block()` 立即返回，不再调用后续插件 |
| 执行超时 | ❌ | **类注释写着「P0：每 plugin 100ms」，但代码中没有任何超时实现**——插件死循环会挂住调用线程 |
| 内存 / CPU 限额 | ❌ | 与网关同 JVM、同线程串行执行，插件可耗尽堆内存 |
| 能力（Capability）强制 | ❌ | 插件声明 `Set<PluginCapability>`，但 `PluginRegistry.findByCapability` **全仓库无调用方**；声明 `AUDIT` 的插件照样能改 body/header |
| 文件 / 网络 / 反射管控 | ❌ | 插件是普通 Java 类，拥有宿主进程全部权限 |

**因此**

- **不要加载不可信的第三方插件**。当前模型等价于「把 jar 丢进 classpath 直接跑」，恶意插件可读取 API Key、发起任意外连、崩溃整个网关。
- 插件仅适用于**内部团队编写、经代码评审**的场景。
- 竞品对照矩阵 row 10「扩展性/插件」标记为 ✅ 时，含义是「有插件机制」，**不含**「有安全沙箱」——对外沟通时不要口径混淆。
- 若要开放插件生态，必须先补齐运行时隔离（Chicory Wasm 或独立进程 / ext_proc 模式），这是前置门槛而非优化项。

**低成本的部分改善**（若不做 Wasm，可考虑的折中项，均未排期）

- 给 `PluginSandbox.execute` 套 `ExecutorService` + `Future.get(timeout)`，兜住死循环；
- 在 `PluginManager` 注册时按 capability 校验插件实际改动的字段，做声明一致性检查；
- 用 `SecurityManager` 替代方案（JDK 24 后已移除）不可行，需走独立进程隔离。

---

## 3. 其他长期内存态组件（背景信息，非本次决策）

以下端口同样只有内存实现，未纳入过 Pg 计划，列出以免误判持久化范围：

| 端口 | 实现 | 备注 |
|---|---|---|
| `CostRepository` | `InMemoryCostRepository` | 成本明细重启丢失（`PgBillingRepository` / `PgBudgetRepository` 覆盖的是账单与预算，不含明细） |
| `PluginRegistry` | `InMemoryPluginRegistry` | 插件启用/禁用状态重启回到 SPI 默认全启用 |
| `AuditRepository` | `InMemoryAuditRepository`，生产可切 `PgAuditStore` | 需 `observability.storage.enabled=true` |
| `SessionRepository` | `InMemorySessionRepository`，生产可切 `RedisSessionRepository` | 默认内存 = 单实例 |
| `ApiKeyStore` | `InMemoryApiKeyStore`，生产用 `JsonFileApiKeyStore` | 落盘 `data/api-keys.json`，非 DB |
| `RateLimiter` | `InMemoryRateLimiter` | 计数不跨副本，多副本下实际限流阈值 = 配置值 × 副本数 |

---

## 4. 维护记录（meta，2026-09-04）

> **2026-09-04 事故说明**：本节原记录「`docs/known-limitations` 分支 + 4 个 commit + 推送受阻」等内容，因当日发生 `git filter-branch` 操作事故（详见 [`docs/git-recovery-and-gitignore-lessons.md`](git-recovery-and-gitignore-lessons.md)），19 轮 commit history（378 commits）全部丢失。本节引用的所有 commit SHA 与分支名均已失效，**保留此处仅为指向事故复盘文档**。

事故后状态：
- 远端 `master` 已重建并推送（2 commits / 1,080 files / ~2 MB）
- 19 轮历史不可恢复；如需回查旧 commit，需从其他 clone / IDE local history / `git fsck --lost-found` 找
- `openspec/changes/add-foundation-skeleton/` 目录在事故中丢失，`AGENTS.md` 中相应行已标记
- 各 R 报告（Round 1-19）顶部均加 disclaimer 说明 SHA 失效

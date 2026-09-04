# Proposal: IAM / RBAC 深化（d1-iam-rbac-deepening）

> **状态**：📝 阶段二待评审（OpenSpec 立项稿）
> **范围聚焦**：把 RBAC 从"决策引擎"推进到"决策治理"——类型化、评估接线、可观测
> **术语锚点**：spec §6.3 / §16.2 / §19.1-19.5 / §13.4 / §22.2
> **Roadmap**：D 阶段路线总览 `docs/superpowers/specs/2026-08-25-d-stage-roadmap.md` §2.1（D1 立项）

---

## 变更概述

本 change 深化 spec §19 RBAC 权限管理的"决策治理"能力，把现有"扁平 grant 判定 + Map 策略存储"的最小可用骨架，升级为"类型化 Role/Permission + 持久化仓储 + 评估接线 + 纵深防御 OTel + UI 三件套"的完整治理体系。一期交付与 spec §19.5 一致（Agent 级 + 模型级；Skill/数据级留二期）。

---

## 动机

1. **现有 RBAC 是"决策"不是"治理"**。`AuthorizationService`（`gateway-domain/iam/AuthorizationService.java`）只读 `principal.agentGrants` / `allowedModels` 两个扁平集合；`AdminPolicyController`（`gateway-interfaces/admin/AdminPolicyController.java`）用 `Map<String, Object>` 存策略（127 行注释承认"评估接线前，策略在 preview 中不生效"）——任何"角色 → 多权限"概念都无法表达。spec §19.1 明确"角色定义、用户/组→角色→权限映射、策略预览"是 §19 三大支柱，缺一即治理残缺。
2. **spec §19.2 record 全部未落地**。`Role / AgentPermission / ModelPermission / SkillPermission / PolicyPreview` 五个类型在 spec 中已有权威定义，但代码里一个都没有。domain 端口 `RoleRepository / RoleBindingRepository / RbacChangePublisher`（roadmap §2.1 列出）同样为空——管理层无法把策略落库、Nacos 无法广播热更、评估链无法消费。
3. **纵深防御两处校验点缺一不可**。spec §6.3 明确"工具注入时 RbacFilter" + "A2A 调用前二次校验"两处必查。当前 `AuthorizationService.checkInvokeAgent` 仅供 `ChatOrchestrator`（编排层）调用；A2A 客户端（`gateway-infra-a2a`）调用前的二次校验 hook 尚未找到接线点——单点失守意味着 LLM 误构造的 tool_call 可直达远程 Agent。
4. **观测与审计断点**。spec §22.2 把 `GRANT_CREATE/UPDATE/DELETE / RBAC_DENIED` 列为审计事件；roadmap §2.1 要求 OTel `rbac.allowed/rbac.denied` 双 Counter 加 `rbac.check_point` 维度。当前 `AdminPolicyController` 写审计但 `AuthorizationServiceImpl` 决策本身**完全无 OTel span attribute**——线上 RBAC 出问题只能事后翻日志，无法看面板。

**不做本 change 的后果**：D2/D3/D4 都依赖"完善的角色模型 + AuthPrincipal"，roadmap §4.1 已显式声明 D2→D1、D3→D1、D4→D1 依赖；D1 不落地，后续三个主题同步阻塞。

---

## What / 范围

### 做（What）

| # | 能力 | 验收口径 |
|---|---|---|
| 1 | 类型化 5 个 record（spec §19.2 逐字落地） | `Role / AgentPermission / ModelPermission / SkillPermission / PolicyPreview` 在 `gateway-domain/iam/` 出现，sealed `Permission` 保留 Skill 子类（一期数据空、可持久化） |
| 2 | 3 个 domain 出站端口 | `RoleRepository / RoleBindingRepository / RbacChangePublisher`（JDK Flow，无框架） |
| 3 | 评估接线（评估链消费仓储） | `AuthorizationService` 注入 `RoleRepository`，决策时按 `principal.user` 查 bindings → 汇总 permissions → 与 `principal.agentGrants/allowedModels` 并集判定 |
| 4 | AdminPolicyController 类型化 | 切到 `Role / Permission` record，删除 `Map<String, Object>`；`/v1/admin/rbac/preview` 走纯函数重放（不读仓储，无副作用） |
| 5 | 纵深防御 A2A 二次校验 hook | `A2aToolPort.invoke()` 前调 `AuthorizationService.checkInvokeAgent(principal, agentName)`；新增 `check_point=a2a` OTel attribute |
| 6 | DENIED 审计 + OTel 双 Counter | `AuthorizationServiceImpl` 决策路径打 `rbac.allowed / rbac.denied` Counter（带 `check_point / tenant / user / agent / model` 维度）；DENIED 同步写 `AuditRepository`（事件类型 `RBAC_DENIED`） |
| 7 | 补 `/v1/admin/roles` REST | `GET/POST/PUT/DELETE /v1/admin/roles` + `GET/POST/DELETE /v1/admin/users/{id}/roles`（spec §19.3 端点逐字）；复用 `GW-4204` 错误码段 |
| 8 | UI 三件套 | 角色管理页（CRUD）+ 用户角色绑定页（搜索用户、勾选角色）+ 策略预览页（输入 userId 调 `/v1/admin/rbac/preview` 展示 allowedAgents/allowedModels） |

### 不做（Non-goals）

- **组/部门管理**（spec §19.5 二期）：`Group` 抽象与 `Group→Role` 绑定留 D 阶段之后
- **Skill 级 / 数据级权限**（spec §19.5 二期）：`SkillPermission` record 落类型但仓储留空，UI 不暴露 Skill 维度勾选
- **策略回滚 / 版本历史**（spec §19 一期范围内本 change 不承担；走 §20 配置中心能力）
- **RBAC 自审计与策略差异对比**：配置中心 §20 已规划配置版本历史，RBAC 走相同路径不重复造轮子
- **AdminPolicyController 旧 `/v1/admin/rbac/policies` 端点改造**：保留作为兼容入口（已被前端 policies.ts 引用），仅在内部把存储换成 `Role` 列表；删除留二期清理 PR

---

## 关键决策点

### 决策点 D1-1：策略存储方式

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A. DB 直存 + 启动加载 + Nacos 广播**（推荐） | 一致性最强、跨实例共享、可走 AdminPolicyController 单一写入路径；Nacos 广播给所有网关节点热加载（spec §19.4）| DB 选型依赖项目（本期 InMemory 实现 + 接口预留，未来切 JPA/Redis）|
| B. 直接走 Nacos Config | 不需 DB；Nacos 即数据源 | 失去 AdminPolicyController 控制权、回滚需 Nacos API、审计一致性差 |

**选 A**。理由：spec §19.4 "策略变更 → Nacos `gateway.rbac.*` → 热更新"明确给出链路，AdminPolicyController 是写入端、仓储是冷启动源、Nacos 是广播通道；本期 `RbacChangePublisher` 是接口、`InMemoryRoleRepository` 是实现，二期无缝切 DB。

### 决策点 D1-2：PolicyPreview 路径

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A. 纯函数重放**（推荐） | 不读仓储、无副作用、可重复调用（幂等）；单测无需 mock | 需把角色/绑定快照作为入参或注入查询服务 |
| B. 走完整评估链 | 与生产路径一致 | 依赖仓储状态、需 mock、有副作用 |

**选 A**。理由：`/v1/admin/rbac/preview` 是"假设按当前策略，用户 X 能用什么"——天然纯函数语义。设计 `PolicyPreviewService.evaluate(roles, bindings, user, tenant) → PolicyPreview`，与 `AuthorizationService` 共享聚合逻辑但独立调用入口。

### 决策点 D1-3：审计写入粒度

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 每次评估（ALLOWED + DENIED）都写 | 完整审计轨迹 | 量级爆炸（一次会话 N 次工具注入评估），性能 + 存储压力 |
| **B. 仅 DENIED 写**（推荐） | 量级可控、与 spec §22.2 "RBAC_DENIED" 事件一致；ALLOWED 走 OTel Counter 即可 | ALLOWED 无审计回溯（但有 trace 与 metrics） |

**选 B**。理由：spec §22.2 授权类别把 `RBAC_DENIED` 单列为审计事件，未要求 `RBAC_ALLOWED`；ALLOWED 走 OTel `rbac.allowed` Counter（带 `check_point` 维度）天然适合高频指标。审计与监控分工明确（§22.1 原则："trace 是性能/调用链、audit 是合规追溯"）。

### 决策点 D1-4：Permission 类型演进

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A. sealed Permission 保留 Skill 子类**（推荐） | 与 spec §19.2 逐字对齐、Pattern Matching 完备、二期 Skill 级落地零改动 | 一期 `SkillPermission` 数据空 |
| B. 一期只暴露 Agent + Model | 简单 | 破坏 spec §19.2 权威定义、二期破坏性升级 |

**选 A**。理由：spec §19.2 已是权威定义，本 change 是"深化落地"而非"重设语义"；sealed interface 在 Java 21 上 Pattern Matching 完备，`case SkillPermission sp → ...` 即便数据空也能编译通过；二期填数据零破坏。

---

## 错误码段分配

对齐 spec §13.4 + roadmap §3 已 Approved 冲突扫描结果（与 D2/D3/D4 零冲突）：

| 错误码 | 触发场景 | 段归属 |
|---|---|---|
| `GW-1003` 无权限 | 复用——`AuthorizationService.checkInvokeAgent/checkUseModel` 抛 `AuthorizationException` 时由 `GlobalExceptionHandler` 映射（spec §13.4 已规划） | GW-1xxx 接入层 |
| `GW-1010` 角色不存在 | 新增——`GET/PUT/DELETE /v1/admin/roles/{id}` 时仓储未命中 | GW-1xxx |
| `GW-1011` 角色绑定冲突 | 新增——同一 user×role 已存在时返回 409 | GW-1xxx |
| `GW-1012` 角色权限非法 | 新增——POST/PUT Role 时 `Permission` JSON 解析失败 / sealed 类型不识别 | GW-1xxx |
| `GW-1013` 用户角色绑定不存在 | 新增——`DELETE /v1/admin/users/{id}/roles/{roleId}` 时仓储未命中 | GW-1xxx |
| `GW-4204` 管理后台 RBAC 错误 | 复用——`/v1/admin/roles` 与 `/v1/admin/users/{id}/roles` REST 的通用 RBAC 端点异常（spec §19.3 已规划） | GW-42xx 管理后台 |

> **段位零冲突**：D1 占 GW-1xxx（1010~1013 增量）+ GW-42xx（复用）；D2 占 GW-43xx（4304~4306 增量）；D3 占 GW-5xxx；D4 占 GW-45xx/6xxx/7xxx。roadmap §3 冲突扫描已通过。

---

## 与现有模块的关系

### 复用（不改）

- **`AuthPrincipal`**（`gateway-domain/iam/AuthPrincipal.java`）：保留扁平 `agentGrants/allowedModels/AuthChannel` 三个字段，新增注释"由 Role/Binding 聚合填充"——评估链沿用既有字段语义
  - `AuthChannel` 枚举预留 D4 OAuth2 / IM 通道身份扩展位；本 change **不**新增条目
- **`AuthorizationService` 接口**（`gateway-domain/iam/AuthorizationService.java`）：4 个方法签名不动
- **`AuthorizationServiceImpl`**（`gateway-infra-security/AuthorizationServiceImpl.java`）：内部从"只读 principal 字段"升级为"读仓储聚合 principal + 字段"，公开 API 不变
- **`AuditRepository`** 与 `AuditEventType.RBAC_DENIED`（若尚未存在则新增）：写入路径完全复用
- **`AdminDiscoveryController`**（`gateway-interfaces/admin/AdminDiscoveryController.java`）：已用 `AuthorizationService`，评估接线后行为自动升级，无需改 controller
- **`ChatOrchestrator`**（`gateway-application/orchestration/ChatOrchestrator.java`）：评估链消费 `AuthorizationService`，自动受益

### 扩展（新增文件，不改既有类）

- `gateway-domain/iam/Role.java` + `Permission.java`（sealed）+ `AgentPermission.java` + `ModelPermission.java` + `SkillPermission.java` + `PolicyPreview.java`
- `gateway-domain/iam/RoleRepository.java` + `RoleBindingRepository.java` + `RbacChangePublisher.java`（JDK Flow 接口）
- `gateway-domain/iam/RoleId.java`（强类型 ID，必要时与现有 `UserId/TenantId` 风格一致）
- `gateway-domain/iam/AuthorizationException.java`（若不存在；统一封装 `GW-1003`）
- `gateway-interfaces/admin/AdminRoleController.java`（新文件，承载 `/v1/admin/roles`）
- `gateway-interfaces/admin/AdminUserRoleController.java`（新文件，承载 `/v1/admin/users/{id}/roles`）
- `gateway-interfaces/admin/AdminRbacPreviewController.java`（新文件，承载 `/v1/admin/rbac/preview` 纯函数评估）
- `gateway-infra-security/RbacInflightPolicy.java`（A2A 调用前二次校验 + OTel check_point=a2a 埋点）
- `gateway-bootstrap/.../InMemoryRoleRepository.java` + `InMemoryRoleBindingRepository.java` + `NacosRbacChangePublisher.java`（占位实现 + 接口签名稳定）

### 重写（无）

零重写。所有变更通过新增文件 + 现有类方法体内改造完成，对外契约（REST 路径、AuthorizationService 接口、AuditRepository 接口）零破坏。

---

## 验收标准

> **详细条款见 `spec.md`**，本节为高层摘要。

1. **类型与契约 SHALL**：spec §19.2 五个 record 在 `gateway-domain/iam/` 落地，sealed `Permission` 含 Skill 子类；通过单元测试验证 record 不可变 + Pattern Matching 完备。
2. **仓储 SHALL**：`RoleRepository / RoleBindingRepository` 提供 `findById / save / delete / findAll`；`RbacChangePublisher.publish(event)` 发布 `Flow.Publisher<RbacChangeEvent>`；通过 PortContractTest 验证零实现也能编译。
3. **评估接线 SHALL**：`AuthorizationServiceImpl` 改造后，单元测试覆盖"仅有 Role 权限" / "仅有 principal 字段权限" / "两者并集" / "DENIED → AuditRepository.append + OTel Counter" 四条路径。
4. **纵深防御 SHALL**：`gateway-infra-a2a` 在 `A2aToolPort.invoke()` 前调 `checkInvokeAgent`，单测覆盖"principal 无权 → 抛 AuthorizationException → 不发起 HTTP"。
5. **OTel SHALL**：`rbac.allowed / rbac.denied` 两个 Counter 出现在 OTel Meter 导出器中，attribute 含 `check_point ∈ {rbac_filter, a2a}`、`tenant`、`user`、`agent/model`；通过 MeterProvider 测试验证。
6. **审计 SHALL**：`AuthorizationServiceImpl` 决策结果为 DENIED 时同步调 `AuditRepository.append`（事件类型 `RBAC_DENIED`），单测覆盖；ALLOWED 路径不写审计。
7. **REST SHALL**：`/v1/admin/roles` CRUD + `/v1/admin/users/{id}/roles` 三操作 + `/v1/admin/rbac/preview` 幂等性，集成测试覆盖 happy path + 错误码（GW-1010/1011/1012/1013/4204）。
8. **UI SHALL**：角色管理页 + 用户角色绑定页 + 策略预览页可独立访问，E2E 测试覆盖"创建角色 → 绑定用户 → preview 看到新权限"主流程。

---

## 风险与缓解

> **三层联动**：本表与 `design.md §8`（设计维度风险）+ `docs/superpowers/specs/2026-08-25-d-stage-roadmap.md §4.2`（D 阶段共性风险）三向交叉引用。spec.md §归档闸门 ⑬ 含映射表。

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 评估链改造破坏现有 `ChatOrchestrator` 行为 | 中 | 高 | 既有 8+ 单测覆盖编排路径；改造后保留 `principal.agentGrants/allowedModels` 直读分支作为 fallback，单测对比 before/after 决策一致 |
| A2A 二次校验引入额外延迟 | 低 | 中 | 单次内存查表（O(role 数量)），p99 增加 < 0.5ms；OTel `rbac.decision.duration` 直方图埋点观察 |
| sealed `Permission` Pattern Matching 不完备（漏分支编译错） | 低 | 低 | Java 21 sealed 强制 exhaustiveness，编译期阻断；CI 必须 JDK 21 |
| `AdminPolicyController` 旧 `/policies` 端点与新 `/roles` 端点并存导致前端困惑 | 中 | 中 | UI 标注"已迁移至角色管理"；旧端点保留仅作为 deprecation 提示，二期清理 PR 跟进 |
| Nacos `gateway.rbac.*` Data ID 与其他模块撞名 | 低 | 中 | 命名遵循 spec §19.4 字面值 + tenant 前缀 `gateway.rbac.{tenant}.roles`；roadmap §3 命名规约已审 |
| 测试覆盖率回归（既有 8 测试不受影响） | 低 | 中 | tasks.md 明确"既有测试零修改"红线；CI 增加 `git diff --name-only main...HEAD` 校验 |

---

## 工作量与阶段切分

> 与 `tasks.md` 同步（tasks.md 由后续 agent 撰写）。

| 阶段 | 工作量 | 关键交付 |
|---|---|---|
| **MVP**（前 2 周）| 类型化 record + 3 个 Port + InMemory 实现 + AdminPolicyController 切类型 + `/v1/admin/roles` REST + AuthorizationService 评估接线 | spec.md SHALL 1-7 + 11 |
| **完整版**（再 +0.5 周）| A2A 二次校验 hook + OTel Counter + DENIED 审计 + UI 三件套 + 集成/E2E 测试 | spec.md SHALL 8-10 + 12 |

总计约 2.5 周，与 roadmap §2.1 一致。

---

## 关联文档

- **D 阶段路线总览**：`docs/superpowers/specs/2026-08-25-d-stage-roadmap.md`（§2.1 D1 立项决策矩阵 + §3 错误码冲突扫描）
- **项目级 spec 主文档**：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§6.3 Agent 级与模型级 RBAC、§16.2 数据模型、§19 RBAC 权限管理、§22.2 审计事件分类、§13.4 错误码规划）
- **D1 子代理阶段一提案**：对话历史（4 个 `backend-architect` 子代理结果整合）
- **前情 change**：`openspec/changes/archive/2026-08-14-add-auth-and-rbac/`（基础认证 + 扁平 grant 已落地）
- **项目规范**：`AGENTS.md`（多 Agent 协同 + OpenSpec 四阶段）
- **本 change design（待写）**：`openspec/changes/d1-iam-rbac-deepening/design.md`
- **本 change spec（本文档姊妹）**：`openspec/changes/d1-iam-rbac-deepening/spec.md`
- **本 change tasks（待写）**：`openspec/changes/d1-iam-rbac-deepening/tasks.md`

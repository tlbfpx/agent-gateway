# Design: D1 IAM / RBAC 深化（d1-iam-rbac-deepening）

> **范围**：spec §19 RBAC 治理深化——类型化、评估接线、纵深防御、可观测、UI 三件套。
> **术语锚点**：spec §6.3 / §16.2 / §19.1-19.5 / §22.2 / §13.4。
> **关联**：proposal.md（动机/范围/决策点）、spec.md（12 条 SHALL）、tasks.md（任务清单）。
> **Roadmap**：`docs/superpowers/specs/2026-08-25-d-stage-roadmap.md` §2.1（D1 立项）。

---

## 1. 架构概览

D1 把 RBAC 从「决策引擎（扁平 grant + Map 存储）」推进到「决策治理（类型化 Role/Permission + 持久化仓储 + 评估接线 + 纵深防御 OTel + UI 三件套）」。新增 6 个 record、3 个 Port、1 个 domain service 与 OTel 埋点；既有 `AuthorizationService` 公开接口签名零破坏，由 `AuthorizationServiceImpl` 内部升级为"扁平字段 + Role/Binding 聚合双路径并集判定"。

```
                agent-gateway-ui  (独立前端仓库)
   pages/Roles/List · pages/UserBindings · pages/Rbac
   调用 /v1/admin/roles · /v1/admin/users/{id}/roles · /v1/admin/rbac/preview
                              │ HTTP + X-API-Key / X-Tenant-Id
                              ▼
┌─ gateway-interfaces ────────────────────────────────────────────────────┐
│ AdminRolesController        (CRUD · spec §19.3)                          │
│ AdminUserRoleController     (user×role bind/unbind)                      │
│ AdminRbacPreviewController  (纯函数 preview · D1-2 决策)                  │
│ AdminPolicyController*      (旧 /policies 路径委托至 /roles · 保留兼容)   │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ 依赖 domain Port
                               ▼
┌─ gateway-domain/iam  (新 record + Port · 零框架) ───────────────────────┐
│ 类型 record:                                                              │
│   Role · sealed Permission (Agent/Model/Skill)                          │
│   PolicyPreview · RbacDecisionEvent · RoleBinding · RbacChangeEvent     │
│   RoleId / UserId / TenantId (强类型)                                    │
│ 出站 Port (JDK Flow):                                                    │
│   RoleRepository · RoleBindingRepository · RbacChangePublisher          │
│ domain service:                                                          │
│   RoleQueryService (preview · rolesOf)                                   │
│ 既有 (签名零变化):  AuthorizationService · AuthPrincipal                  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ 实现
                               ▼
┌─ gateway-infra-security  (评估接线 + 审计 + OTel) ──────────────────────┐
│ AuthorizationServiceImpl      (注入 RoleRepo · 决策并集 · 埋点)          │
│ InMemoryRoleRepository        (ConcurrentHashMap · @ConditionalOnMissing)│
│ InMemoryRoleBindingRepository (同上)                                     │
│ NacosRbacChangePublisher      (gateway.rbac.{tenant}.roles)             │
│ RbacInflightPolicy            (A2A 二次校验 + OTel check_point=a2a)     │
│ RbacMetrics                   (OTel Counter · rbac.allowed/denied)      │
│ RbacAuditEmitter              (DENIED 写 AuditRepository · catch+warn)  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌─ gateway-bootstrap  (Spring Bean 装配 + Flyway) ────────────────────────┐
│ InfraSecurityAutoConfiguration (扩展 · 装配 3 个 Port Bean)              │
│ Flyway V<n>__add_rbac_tables.sql  (rbac_role / rbac_role_binding)       │
└─────────────────────────────────────────────────────────────────────────┘
```

**横切依赖**：spec §22 `AuditRepository` + `AuditEventType.RBAC_DENIED`（若不存在则新增）；spec §13.4 错误码 `GW-1003`（复用）/ `GW-1010~1012`（新增）/ `GW-4204`（复用）。

---

## 2. 技术决策

### 2.1 持久化方案（呼应 D1-1）

| 项 | 选 | 理由 |
|---|---|---|
| **写入端** | `AdminRolesController` → `RoleRepository.save` → `RbacChangePublisher.publish` | 单一写入路径，审计/广播/缓存失效三处自动联动 |
| **冷启动源** | `InMemoryRoleRepository`（`ConcurrentHashMap<TenantId, ConcurrentHashMap<RoleId, Role>>`）| 与既有 `InMemoryApiKeyStore` 风格一致；DB 实现留二期 |
| **跨实例广播** | Nacos `gateway.rbac.{tenant}.roles` Data ID（spec §19.4 字面值）| 复用既有 `gateway-infra-nacos` 客户端，零增量运维 |
| **读路径** | 本地 InMemory Map 直查 | 单实例内存访问，p99 < 0.5ms；评估链 O(role 数量) |
| **后续演进** | 二期 JPA 通过 `@ConditionalOnMissingBean` 覆盖 InMemory；接口签名零破坏 | 与 roadmap §2.1 D1「无缝切 DB」一致 |

### 2.2 缓存失效机制

`RbacChangePublisher` 走 JDK `Flow.Publisher<RbacChangeEvent>`，Nacos 实现是其中一种 subscriber：

```
AdminRolesController.create(role)
  ├─ roleRepository.save(tenant, role)
  ├─ rbacChangePublisher.publish(RbacChangeEvent(kind=ROLE_UPSERT, ...))
  │    └─ NacosRbacChangePublisher.onNext → nacosClient.publishConfig("gateway.rbac.{tenant}.roles", json)
  │    └─ (本地) InMemoryRoleRepository.onNext → reload 本实例缓存（write-through）
  └─ auditRepository.append(GRANT_UPDATE)
```

失败语义：Nacos publish 失败 → catch + log warn，**不**回滚 DB（决策点：先本地写成功即对本地决策生效，其他实例短期不一致由 Nacos 心跳续期兜底）。

### 2.3 PolicyPreview 纯函数语义（呼应 D1-2）

`/v1/admin/rbac/preview` 不读仓储、不写审计、不上 OTel。`RoleQueryService.evaluate(roles, bindings, user, tenant) → PolicyPreview`：过滤 `bindings` 命中的 `Role`，聚合 `permissions` 中 `AgentPermission.agentName` / `ModelPermission.models` 得 `allowedAgents` / `allowedModels`，构造 `PolicyPreview(user, tenant, agents, models)`。

- **幂等保证**：同一入参连发 N 次结果 `equals()` 一致（spec §GW-RBAC-011）
- **不入 OTel**：`checkPoint=preview` 仅用于单测内部追踪（spec §GW-RBAC-010 注释）
- **不写审计**：D1-3 决策——仅 DENIED 写表，preview 是仿真而非实际决策

### 2.4 审计写入时机（呼应 D1-3）

| 决策结果 | 写 AuditRepository（spec §22.2）| OTel Counter |
|---|---|---|
| ALLOWED | ❌（高频，性能 + 存储压力；spec §22.2 未要求）| ✅ `rbac.allowed` |
| DENIED | ✅ AuditEventType.RBAC_DENIED | ✅ `rbac.denied` |
| 审计写入失败 | catch + log warn，**不**阻断主决策 | — |

写入字段（spec §GW-RBAC-009 字面值）：`auditId, tenant, user, actorType=HUMAN, eventType=RBAC_DENIED, timestamp, resource="rbac:agent"|"rbac:model", resourceRef=agentName|modelId.value(), action="denied", result=FAILURE, detail="reason={no_grant|no_role_binding|no_model_permission};check_point={rbac_filter|a2a}"`

---

## 3. 模块划分

### 3.1 gateway-domain/iam（新增文件清单）

- **record**：`RoleId`（强类型 ID）· `Role` `Role(RoleId, name≤64, description≤256, Set<Permission> permissions≤100)`（`Set.copyOf` 不可变）· `Permission` sealed (`permits AgentPermission, ModelPermission, SkillPermission`) · `AgentPermission` / `ModelPermission` / `SkillPermission`（spec §19.2 字段对齐；一期 `SkillPermission` 数据空，D1-4 决策）· `PolicyPreview` · `RbacDecisionEvent` · `RoleBinding` · `RbacChangeEvent`
- **Port**：`RoleRepository` · `RoleBindingRepository` · `RbacChangePublisher`（JDK Flow，零框架，全部租户隔离；详见 spec §GW-RBAC-002）
- **domain service**：`RoleQueryService` —— `rolesOf(tenant, user)` + `preview(tenant, user, List<Role> snapshot)` 纯函数
- **Port contract test**：`RoleRepositoryContractTest` / `RoleBindingRepositoryContractTest` / `RbacChangePublisherContractTest`（InMemory 桩验证零实现可编译）
- **既有类型**：`AuthorizationService.java` **接口签名零变化**（仅 javadoc 追加 Role 决策路径注释）· `AuthPrincipal.java` **字段零变化**（仅 javadoc 追加「由 Role/Binding 聚合填充」）

### 3.2 gateway-infra-security（**推荐并入，不新建 gateway-infra-rbac**）

> **D1-终审决定**：并入 `gateway-infra-security`，降低 Maven 模块数；待 D2/D3 引入更多 RBAC 相关组件时再拆分。

- **既有改造**：`AuthorizationServiceImpl`（构造函数新增 `RoleRepository / RoleBindingRepository` 依赖；`canInvokeAgent` 决策路径升级为「扁平优先 + Role/Binding 聚合」并集；DENIED 路径调 `RbacAuditEmitter.append` + OTel Counter；公开方法签名零变化）· `InfraSecurityAutoConfiguration`（`@Bean` 装配 3 个 Port 默认 InMemory + `RbacMetrics` + `RbacAuditEmitter`）
- **新增**：`InMemoryRoleRepository` / `InMemoryRoleBindingRepository`（`ConcurrentHashMap` 嵌套结构 · `@ConditionalOnMissingBean`）· `NacosRbacChangePublisher`（复用 `gateway-infra-nacos` 的 nacos-client；Data ID `gateway.rbac.{tenant}.roles`）· `RbacInflightPolicy`（A2A 调用前二次校验 hook · 委托 `AuthorizationService.checkInvokeAgent` + OTel `check_point=a2a`）· `RbacMetrics`（OTel Counter 注册）· `RbacAuditEmitter`（DENIED 写 `AuditRepository` + catch+warn）
- **测试**：`AuthorizationServiceImplTest`（既有 8 测试零修改 + 新增 4 条）· `RbacAuditEmitterTest` · `RbacMetricsTest` · `InMemory*RepositoryTest`

### 3.3 gateway-interfaces（新增/修改 REST）

- **新增 `AdminRolesController`** `/v1/admin/roles` （GET/POST/PUT/DELETE，spec §19.3 逐字对齐）
- **新增 `AdminUserRoleController`** `/v1/admin/users/{id}/roles`（GET/POST/DELETE；重复绑定返回 409 + `GW-1011`）
- **新增 `AdminRbacPreviewController`** `/v1/admin/rbac/preview`（POST，纯函数 spec §GW-RBAC-011；同请求连发幂等）
- **修改 `AdminPolicyController`** `/v1/admin/rbac/policies` 保留，委托至 `AdminRolesController`，响应头 `Deprecation: true`；字段从 `Map<String,Object>` 切到 `RoleRepository`
- **测试**：`AdminRoleControllerIT`（MockMvc）· `AdminRbacPreviewControllerIT`（幂等 10 次）· `AdminPolicyControllerTest`（委托路径 + Deprecation header）

### 3.4 agent-gateway-ui（新增页面，路由表扩展）

```
/admin/rbac/roles          → pages/Roles/List.tsx + EditDrawer.tsx + columns.tsx
/admin/rbac/users/:id/roles → pages/UserBindings.tsx
/admin/rbac/preview         → pages/Rbac.tsx (现有扩展 · 或新建 PreviewPanel.tsx)
```

- 侧栏「权限管理」分组：现有「策略预览」项保留 + 新增「角色管理」/「用户绑定」两项
- 路由表更新：`agent-gateway-ui/src/routes.tsx` 新增两条路由 + 侧栏菜单 entries
- 字段上限（spec §GW-RBAC-012 契约）：`Role.name ≤ 64`、`description ≤ 256`、`permissions.size ≤ 100`、`UserId` 正则 `/^u-[a-zA-Z0-9_-]{1,64}$/`

---

## 4. 数据模型

### 4.1 表结构（Flyway `V<n>__add_rbac_tables.sql` 草案，与 spec §16.2 风格对齐）

```sql
-- rbac_role：租户维度角色定义
CREATE TABLE rbac_role (
    id           VARCHAR(64)  NOT NULL,                       -- RoleId.value() 形式："r-<ulid>"
    tenant_id    VARCHAR(64)  NOT NULL,                       -- TenantId.value()
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(256) NOT NULL DEFAULT '',
    permissions  JSONB        NOT NULL,                       -- Set<Permission> 序列化为 [{kind:"agent", agentName:"...", allowedSkills:[...]}, ...]
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT rbac_role_permissions_size_chk CHECK (jsonb_array_length(permissions) <= 100)
);
CREATE INDEX idx_rbac_role_tenant ON rbac_role(tenant_id);

-- rbac_role_binding：用户 × 角色 多对多（租户隔离）
CREATE TABLE rbac_role_binding (
    tenant_id   VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,                         -- UserId.value()
    role_id     VARCHAR(64) NOT NULL,                         -- RoleId.value()
    actor       VARCHAR(64) NOT NULL,                         -- 绑定操作人（admin）
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT fk_rbac_role_binding_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES rbac_role(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_rbac_role_binding_user ON rbac_role_binding(tenant_id, user_id);
```

> **一期存储形态**：`permissions` 用 JSONB 存 `Set<Permission>` 序列化结果（spec §16.2 风格一致）；Sealed 子类通过 `kind` discriminator 区分。`rbac_permission` 物化表留二期 Skill 检索需求出现时再建。

### 4.2 关键 record 字段汇总（沿用 proposal §4.2 · spec §19.2 字面值）

| record | 字段（紧凑） |
|---|---|
| `Role` | `RoleId id, String name≤64, String description≤256, Set<Permission> permissions≤100`（`Set.copyOf` 不可变）|
| `AgentPermission` | `String agentName, Set<String> allowedSkills`（一期 allowedSkills 可空）|
| `ModelPermission` | `Set<ModelId> models`（≥1，与 `AuthPrincipal.allowedModels` 同类型）|
| `SkillPermission` | `String agentName, String skillName`（一期类型在、数据空；D1-4 决策）|
| `PolicyPreview` | `UserId user, TenantId tenant, Set<String> allowedAgents, Set<ModelId> allowedModels` |
| `RbacDecisionEvent` | `eventId, tenant, user, agentName?, model?, CheckPoint, DecisionReason, allowed, timestamp`（OTel + 审计共享载体）|
| `RbacChangeEvent` | `Kind ∈ {ROLE_UPSERT, ROLE_DELETE, BIND, UNBIND}, tenant, roleId, userId?, actor, timestamp` |
| `RoleBinding` | `TenantId tenant, UserId user, RoleId roleId`（仓储内部；表的主键三元组）|

---

## 5. 关键交互流

### 5.1 角色管理 CRUD 流程

```
HTTP POST/PUT/DELETE /v1/admin/roles[/{id}]    (X-API-Key + X-Tenant-Id 鉴权)
        │
        ▼
AdminRolesController ── Bean Validation ── GW-1012 触发
        ├─ roleRepository.save(tenant, role)             ── ConcurrentHashMap put
        ├─ rbacChangePublisher.publish(ROLE_UPSERT, ...)  → nacosClient.publishConfig("gateway.rbac.{tenant}.roles", json)
        ├─ auditRepository.append(GRANT_CREATE/UPDATE/DELETE)
        └─ 201 / 200 / 204 返回              (其他实例) Nacos 订阅回调 → 本实例 reload（write-through）
```

### 5.2 决策评估流程（重点 · spec §GW-RBAC-005/008/009）

```
调用方 (ChatOrchestrator 注入时 / A2A 调用前 / 直接评估)
  │
  ▼
AuthorizationServiceImpl.canInvokeAgent(principal, agentName)
  ├─ 检查 1: principal.agentGrants (扁平字段 · 兼容路径)        → 命中 → recordAllowed → return true
  ├─ 检查 2: roleBindingRepository.findByUser → 对每个 RoleId 查 Role → 聚合 AgentPermission.agentName 命中
  │                                                                  → 任一命中 → recordAllowed → return true
  └─ 都未命中 → recordDenied(reason, ...)
                  ├─ OTel Counter "rbac.denied" 加 attribute (check_point, tenant, user, agent, reason)
                  └─ RbacAuditEmitter.append(AuditEventType.RBAC_DENIED, ...)   ← 仅 DENIED 写表
              → checkInvokeAgent 抛 AuthorizationException → GW-1003
```

OTel Counter 注册（spec §GW-RBAC-008 字面值）：`rbac.allowed` attribute = `check_point ∈ {rbac_filter, a2a}, tenant, user, agent/model, decision="allowed"`；`rbac.denied` attribute = `check_point, tenant, user, agent, decision="denied", reason ∈ {no_grant, no_role_binding, no_model_permission}`。通过 `OpenTelemetry.global().getMeter("gateway.rbac").counterBuilder(...).build()` 注册。

### 5.3 PolicyPreview 流程（纯函数 · spec §GW-RBAC-011）

```
HTTP POST /v1/admin/rbac/preview   { userId, tenantId? }
        │
        ▼
AdminRbacPreviewController
        ├─ roleBindingRepository.findByUser(tenant, user)  ← 读仓储（一次性快照）
        ├─ roleRepository.findAll(tenant)                   ← 读仓储（一次性快照）
        ├─ RoleQueryService.preview(roles, bindings, user, tenant)
        │       └─ evaluate(roles, bindings, user, tenant) → PolicyPreview (纯函数)
        │             └─ 不读仓储 / 不写审计 / 不上 OTel
        └─ 200 PolicyPreview    （同请求连发 N 次 → N 次结果 equals 一致 · 幂等）
```

### 5.4 纵深防御两处校验（spec §6.3 · §GW-RBAC-006/010）

```
入口 1: ChatOrchestrator.buildTools(principal)                    入口 2: A2aToolPort.invoke(principal, ...)
        │                                                                │
        ▼                                                                ▼
   checkInvokeAgent(...)                                          checkInvokeAgent(...)
        │ OTel: rbac.check_point="rbac_filter"                          │ OTel: rbac.check_point="a2a"
        │ DENIED → AuthorizationException                               │ DENIED → AuthorizationException
        │         → 不进入 LLM 工具列表                                   │         → 不发起 HTTP 请求
        ▼                                                                ▼
   (工具列表注入 LLM)                                                HTTP POST {remote-agent}/...
```

**两处 OTel 属性差异**：`check_point` 维度（rbac_filter / a2a）落到 Counter attribute；任一入口 DENIED 都会计 `rbac.denied{check_point=...}`，便于面板按入口分流定位。

---

## 6. 与归档 change 的接口边界

### 6.1 与 `add-auth-and-rbac`（已归档）的关系

| 既有类 | 本 change 处理 | 边界 |
|---|---|---|
| `AuthorizationService`（gateway-domain/iam，4 方法签名）| **零变化**（仅 javadoc 追加 Role 决策路径注释）| 接口签名不动 |
| `AuthorizationServiceImpl`（gateway-infra-security）| **方法体内改造**：构造函数追加 2 个依赖、决策升级为并集、DENIED 路径加 OTel + 审计 | 公开方法签名零变化；既有 8 条单测零修改仍全绿（决策一致性证据 · spec §GW-RBAC-005 验收门）|
| `AuthPrincipal`（gateway-domain/iam，record）| **零变化**（仅 javadoc 追加「由 Role/Binding 聚合填充」）| 字段不动；扁平字段保留作为兼容 fallback |
| `AdminPolicyController`（gateway-interfaces/admin）| **改造**：字段从 `Map<String,Object>` 切到 `RoleRepository`；CRUD 委托至 `AdminRolesController`；响应头 `Deprecation: true` | 旧 `/v1/admin/rbac/policies` 路径保留；前端 `policies.ts` 兼容 |
| `ApiKeyAuthenticator` / `Authenticator` | **零变化** | 本 D 阶段不动认证 |
| `AuditRepository` / `AuditEventType` | **扩展**：`AuditEventType.RBAC_DENIED` 若 spec §22.2 已规划则复用，否则新增（roadmap §2.1 已声明归属 D1）| 接口签名不动 |
| `ChatOrchestrator`（gateway-application/orchestration）| **零变化** | 评估链消费 `AuthorizationService`，自动受益 |

### 6.2 与 D2 / D4 阶段预留接口

| 后续阶段 | D1 预留动作 |
|---|---|
| **D2** 多租户配额 + 成本计费 | `TenantId` 复用、`AuthPrincipal.allowedModels` 含义不变；无新增字段 |
| **D4** 开放 API + IM + Webhook | `AuthPrincipal` 字段不动；D4 在 `AuthChannel` 枚举追加条目（OAuth2 / IM 通道身份）|
| **D3** RAG 知识库 | sealed `Permission` 已保留 `SkillPermission` 子类，二期填数据零破坏（D1-4 决策）|

---

## 7. 测试策略（呼应 spec.md §验收判定）

### 7.1 单元测试（≥ 48 个新测试 · 与 spec.md §验收判定 表逐条对齐）

- **类型层**：`RoleTest`(5) · `AgentPermissionTest`/`ModelPermissionTest`/`SkillPermissionTest`(3+3+3) · `PolicyPreviewTest`(2) · `PolicyEvaluatorTest`(3) — record 不可变 + Pattern Matching exhaustiveness + equals/hashCode
- **Port 层**：`RoleRepositoryContractTest` / `RoleBindingRepositoryContractTest` / `RbacChangePublisherContractTest`(2+2+2) — 零实现可编译 + InMemory 桩契约
- **InMemory 实现**：`InMemoryRoleRepositoryTest` / `InMemoryRoleBindingRepositoryTest`(4+4) — CRUD + 多租户隔离
- **domain service**：`RoleQueryServiceTest`(4) — preview 纯函数 + 幂等（连发 10 次 equals）
- **评估接线**：`AuthorizationServiceImplTest`（既有 8 测试零修改 + 新增 4 条决策路径） — 仅 Role / 仅 principal / 并集 / 都无 → 决策一致性证据
- **可观测**：`RbacMetricsTest`(2) · `RbacAuditEmitterTest`(2) — 7+3 Counter 断言 · DENIED 写 1 次 / ALLOWED 写 0 次
- **check_point 分流**：`RbacCheckPointTest`(2) — rbac_filter / a2a / preview 不上 OTel
- **REST 委托**：`AdminPolicyControllerTest`（既有 + 新 5） — 委托路径 + Deprecation header
- **A2A hook**：`A2aToolPortRbacHookTest`(2) — WireMock 零 HTTP（DENIED）+ 合法路径流式返回
- **REST 新端点**：`AdminRoleControllerTest`(8) — 5 端点 × {成功/400/403/404/409} + Bean Validation；`AdminRbacPreviewControllerTest`(1) — 幂等 10 次 equals

### 7.2 集成测试

- `RbacChangePublisherNacosIT` — Nacos Testcontainers 模拟广播 → 订阅端 reload
- `RbacAuditEmitterIT` — DENIED 路径 → `AuditRepository.append` 落库 + 查询回读
- `RoleRepositoryJpaIT`（**二期落地**，本期 InMemory 覆盖；保留 4.1 SQL 脚本作为二期里程碑）

### 7.3 E2E（Playwright + 后端真启动）

`RbacE2ETest`：登录管理后台 → 创建角色 `role-test` 含 `AgentPermission("echo-agent")` → 绑定 `user-test` → preview 断言 `allowedAgents` 含 `["echo-agent"]` → 删除角色 → preview 断言 `allowedAgents` 为空。

---

## 8. 风险与缓解（design 维度 · 与 proposal §风险、roadmap §4.2 协同）

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **模块边界漂移**：注入 `RoleRepository` 后与 D2 配额产生循环依赖 | 低 | 中 | RoleRepo 留 `gateway-infra-security`；D2 反向消费 `AuthPrincipal` 字段，不反向依赖 RBAC |
| **接口稳定性**：新 record/Port 触达 D4 OAuth2 import | 中 | 中 | 新类型全放 `gateway-domain/iam`；`AuthorizationService` 签名零破坏，其他模块零 import 改动 |
| **数据库迁移**：Flyway V 序号冲突 | 低 | 高 | 选 V 后三位最大未占用；脚本先于 `RoleRepositoryJpa` 二期提交（一期 InMemory 不需 Flyway）|
| **Nacos Data ID 撞名** | 低 | 中 | 严格 `gateway.rbac.{tenant}.roles`（spec §19.4 字面值）|
| **sealed 子类二期编译失败** | 中 | 中 | 本 change 集中 `PolicyEvaluator` 1 处模式匹配；二期新增子类仅需改 1 处 + 加测试 |
| **OTel Meter 名冲突** | 低 | 低 | meter name `gateway.rbac`（roadmap §3 命名规约）|

---

## 9. 真实验证命令

```bash
# 1. 模块级单测
mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean test
# 2. RBAC 相关测试过滤
mvn -pl gateway-domain test -Dtest='*Rbac*'
mvn -pl gateway-infra-security test -Dtest='*Rbac*,*Authorization*,*Policy*'
# 3. 覆盖率（JaCoCo ≥ 80% 业务逻辑）
mvn -pl gateway-domain,gateway-infra-security -am verify -Pcoverage
# 4. 后端冒烟
curl -H "X-API-Key: $ADMIN_KEY" -H "X-Tenant-Id: primary" http://localhost:8080/v1/admin/roles   # 200 + []
# 5. UI 构建
cd agent-gateway-ui && npm run build && npm run test -- --coverage
# 6. 全量归档闸门
./verify.sh
```

**归档闸门**：① 12 条 SHALL 全绿 ② `mvn clean test` 全模块通过 + JaCoCo ≥ 80% ③ 四件套齐全 ④ 既有 `AuthorizationServiceImplTest` 零修改仍绿（决策一致性证据）⑤ spec §19.2 record 逐字对齐（5 record + 1 sealed interface）⑥ 错误码段位零冲突（roadmap §3 已 Approved）。

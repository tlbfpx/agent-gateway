# Spec: D1 IAM / RBAC 深化（可测试需求条款）

> **状态**：📝 阶段二待评审（OpenSpec 立项稿）
> **性质**：本文件是 TDD 阶段的权威需求源，每条 SHALL 都要有对应测试覆盖。
> **范围聚焦**：spec §19 RBAC 治理深化——类型化、评估接线、纵深防御、可观测、UI 三件套
> **术语锚点**：spec §6.3 / §16.2 / §19.1-19.5 / §13.4 / §22.2
> **关联**：`proposal.md`（动机/范围）、`design.md`（待写）、`tasks.md`（待写）

## 范围与术语

### 范围
本 spec 覆盖 RBAC 决策治理能力的"深化"——把现有"扁平 grant 判定 + Map 策略存储"的最小骨架升级为类型化 Role/Permission 模型 + 持久化仓储 + 评估接线 + 纵深防御 OTel + UI 三件套。一期仅承担 Agent 级 + 模型级（spec §19.5）；Skill 级、数据级权限留二期。

### 术语澄清

| 术语 | 定义 | spec 锚点 |
|---|---|---|
| **`AuthPrincipal`** | 已认证主体：`UserId/TenantId/agentGrants(扁平 Set<String>)/allowedModels(扁平 Set<ModelId>)/AuthChannel`。本 change 不动 record 签名，评估链内部从 Role/Binding 聚合填充扁平字段 | §6.3 + §3.3 |
| **`AuthorizationService`** | domain 端口：`canInvokeAgent/canUseModel/canInvokeAgent(check+throw)/canUseModel(check+throw)` 四方法签名不动 | §6.3 |
| **`Role`** | spec §19.2 字面值：`Role(RoleId id, String name, String description, Set<Permission> permissions)` | §19.2 |
| **`Permission`** | sealed interface，子类 `AgentPermission / ModelPermission / SkillPermission`；本 change 落地 sealed 形态但 Skill 子类一期数据为空 | §19.2 |
| **`PolicyPreview`** | `PolicyPreview(UserId user, TenantId tenant, Set<String> allowedAgents, Set<ModelId> allowedModels)` | §19.2 |
| **`RoleRepository`** | domain 出站端口：`findById / findAll / save / delete`（租户隔离） | §19.4 |
| **`RoleBindingRepository`** | domain 出站端口：`findByUser / bind / unbind` | §19.4 |
| **`RbacChangePublisher`** | domain 出站端口（JDK Flow）：`publish(event): Flow.Publisher<RbacChangeEvent>` | §19.4 |

## SHALL 条款（12 条，分 4 组）

> 编号规则：`GW-RBAC-NNN`（N=1..12）。每条 SHALL 必有对应单元 / 集成 / E2E 测试（见 §验收判定）。

### 第 1 组：角色与权限类型化（条款 1-4）

#### `GW-RBAC-001` Role / Permission 五个 record 落地

**MUST**：在 `gateway-domain/iam/` 包下新增 6 个 record/interface，签名逐字对齐 spec §19.2：

```java
public record Role(RoleId id, String name, String description, Set<Permission> permissions) {}
public sealed interface Permission permits AgentPermission, ModelPermission, SkillPermission {}
public record AgentPermission(String agentName, Set<String> allowedSkills) {}
public record ModelPermission(Set<ModelId> models) {}
public record SkillPermission(String agentName, String skillName) {}    // 一期类型在、数据空
public record PolicyPreview(UserId user, TenantId tenant,
                            Set<String> allowedAgents, Set<ModelId> allowedModels) {}
```

- `Set<Permission> permissions` 不可变（canonical constructor `Set.copyOf`）
- `RoleId` 强类型（与 `UserId/TenantId` 风格一致，封装 `String`）
- `Permission` sealed 必须保留 `SkillPermission` 子类（D1-4 决策）

**测试**：单元测试 `RoleTest / AgentPermissionTest / ModelPermissionTest / SkillPermissionTest / PolicyPreviewTest` 各覆盖 record 不可变、equals/hashCode、Pattern Matching exhaustiveness。

#### `GW-RBAC-002` 三个 domain 出站端口（JDK Flow，零框架）

**MUST**：在 `gateway-domain/iam/` 下新增三个 port：

```java
public interface RoleRepository {
    Optional<Role> findById(TenantId tenant, RoleId roleId);
    List<Role> findAll(TenantId tenant);
    void save(TenantId tenant, Role role);
    void delete(TenantId tenant, RoleId roleId);
}

public interface RoleBindingRepository {
    List<RoleId> findByUser(TenantId tenant, UserId user);
    void bind(TenantId tenant, UserId user, RoleId roleId);
    void unbind(TenantId tenant, UserId user, RoleId roleId);
}

public interface RbacChangePublisher {
    Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event);
    // event: { kind: ROLE_*|BINDING_*, tenant, roleId?, userId?, actor, timestamp }
}
```

- 端口签名仅依赖 JDK 标准库（`java.util.concurrent.Flow`），不引入 Spring/Reactor
- 所有方法租户隔离（`TenantId` 为第一参数）
- `PortContractTest` 验证"零实现也能编译通过"（与既有风格一致）

**测试**：单元测试 `RoleRepositoryContractTest / RoleBindingRepositoryContractTest / RbacChangePublisherContractTest` 用 InMemory 桩验证契约。

#### `GW-RBAC-003` `Permission` sealed Pattern Matching 完备

**MUST**：在 `PolicyEvaluator` 中实现 `evaluatePermission(Permission p): PermissionDecision` 模式匹配：

```java
return switch (p) {
    case AgentPermission ap  -> decideAgent(ap);
    case ModelPermission mp  -> decideModel(mp);
    case SkillPermission sp  -> decideSkill(sp);  // 一期永远返回 ALLOWED-with-skip
};
```

- Java 21 sealed exhaustiveness 编译期阻断；CI 强制 JDK 21
- `case SkillPermission sp` 允许抛 `UnsupportedOperationException("deferred to phase 2")` 便于二期填数据定位

**测试**：单元测试 `PolicyEvaluatorTest` 覆盖三种 Permission 类型的输入与预期决策。

#### `GW-RBAC-004` Role / Binding 仓储的 InMemory 占位实现

**MUST**：在 `gateway-bootstrap`（或 `gateway-infra-security`）下提供：
- `InMemoryRoleRepository`（`ConcurrentHashMap<TenantId, ConcurrentHashMap<RoleId, Role>>`，线程安全）
- `InMemoryRoleBindingRepository`（`ConcurrentHashMap<TenantId, ConcurrentHashMap<UserId, Set<RoleId>>>`）
- 两个实现标注 `@ConditionalOnMissingBean` 以便后续 DB 实现覆盖

**测试**：单元测试各覆盖 `findById / findAll / save / delete / bind / unbind` 路径 + 多租户隔离（不同 TenantId 数据互不可见）。

### 第 2 组：RBAC 评估接线（条款 5-7）

#### `GW-RBAC-005` `AuthorizationServiceImpl` 评估链消费 RoleRepository

**MUST**：`AuthorizationServiceImpl`（`gateway-infra-security/AuthorizationServiceImpl.java`）构造函数新增 `RoleRepository / RoleBindingRepository` 依赖。`canInvokeAgent(principal, agentName)` 决策顺序：

1. **优先路径**：查 `principal.agentGrants`（现有扁平字段，保留向后兼容）
2. **增强路径**：若 1 返回 false，查 `RoleBindingRepository.findByUser(principal.tenant, principal.user)` → 对每个 `RoleId` 查 `RoleRepository.findById` → 汇总 `permissions` 中 `AgentPermission.agentName == agentName` 的项
3. **决策**：1 或 2 任一为 true → ALLOWED

`canUseModel` 同理（`principal.allowedModels` 优先，再聚合 `ModelPermission.models`）。

- 公开 API 签名（4 个方法）零变化
- 既有用例（`AuthorizationServiceImplTest`）零修改仍全绿——验证决策一致性

**测试**：单元测试 `AuthorizationServiceImplTest` 新增 4 条用例：① `只有 Role 权限` ② `只有 principal 字段权限` ③ `两者并集` ④ `都无`（均对照 1-3 决策顺序断言 ALLOWED/DENIED）。

#### `GW-RBAC-006` 纵深防御：A2A 调用前二次校验

**MUST**：在 `gateway-infra-a2a/A2aToolPort.invoke(principal, agentName, request)` 路径最前部（HTTP 调用前）插入：

```java
authorizationService.checkInvokeAgent(principal, agentName);
// OTel: span.setAttribute("rbac.check_point", "a2a");
```

- 校验失败抛 `AuthorizationException`（即 `GW-1003`），不发起任何 HTTP 请求
- 校验成功打 OTel span attribute `rbac.check_point=a2a`
- 不阻塞合法调用：成功路径耗时增加 < 1ms

**测试**：单元测试 `A2aToolPortRbacHookTest`（`gateway-infra-a2a` 模块）：WireMock 模拟远端 Agent，传入无权限 principal → 抛 `AuthorizationException` + WireMock 验证 **零 HTTP 请求**；集成测试合法 principal → WireMock 收到请求 + 响应正常流式返回。

#### `GW-RBAC-007` `AdminPolicyController` 从 `Map<String,Object>` 切到类型化仓储

**MUST**：`AdminPolicyController`（`gateway-interfaces/admin/AdminPolicyController.java`）改造：
- 字段从 `Map<String, Map<String, Object>> policies` 切到 `RoleRepository roleRepository`
- `listPolicies()` 从仓储读 `List<Role>`，按 `permissions` 大小倒序排列
- `create/update/delete` 操作仓储 + 调 `RbacChangePublisher.publish(new RbacChangeEvent(...))`
- 旧 `/v1/admin/rbac/policies` 路径保留但内部委托给 `/v1/admin/roles`（方法 `@Deprecated`、响应头 `Deprecation: true`）
- 旧端点审计写入路径保留（`AuditEventType.GRANT_CREATE/UPDATE/DELETE`）；新端点同步写同类型审计

**测试**：单元测试 `AdminPolicyControllerTest`（既有 21 行测试）+ 新增 5 条类型化用例覆盖 `Role` 创建/查询/更新/删除 + 委托路径。

### 第 3 组：可观测与审计（条款 8-10）

#### `GW-RBAC-008` OTel Counter `rbac.allowed` / `rbac.denied`

**MUST**：`AuthorizationServiceImpl` 决策路径（`checkInvokeAgent / checkUseModel`）打 OTel Counter：

| Counter | 触发 | Attributes |
|---|---|---|
| `rbac.allowed` | ALLOWED | `check_point ∈ {rbac_filter, a2a}`、`tenant`、`user`、`agent`/`model`、`decision=allowed` |
| `rbac.denied` | DENIED | `check_point`、`tenant`、`user`、`agent`、`decision=denied`、`reason ∈ {no_grant, no_role_binding, no_model_permission}` |

- Counter 通过 `OpenTelemetry.global().getMeter("gateway.rbac").counterBuilder(...).build()` 注册
- `reason` 由实现内 switch 决策路径填充，不允许 `unknown`

**测试**：单元测试 `RbacMetricsTest` 用 `InMemoryMetricExporter` 验证：
- 调用 `checkInvokeAgent` 10 次（7 ALLOWED + 3 DENIED）→ 导出 7 条 `rbac.allowed` + 3 条 `rbac.denied`
- 每条 Counter 的 attribute 集合与断言一致（重点 `check_point` / `reason`）

#### `GW-RBAC-009` DENIED 路径同步写 `AuditRepository`

**MUST**：`AuthorizationServiceImpl` 决策结果为 DENIED 时（条款 8 的 `rbac.denied` Counter 同一路径），同步调：

```java
auditRepository.append(new AuditRepository.AuditLog(
    auditId, principal.tenant, principal.user.value(),
    AuditRepository.AuditLog.ActorType.HUMAN,
    AuditEventType.RBAC_DENIED,                  // 新增（spec §22.2 已规划）
    Instant.now(),
    "rbac:" + (agentName != null ? "agent" : "model"),
    agentName != null ? agentName : modelId.value(),
    "denied",
    AuditRepository.AuditLog.Result.FAILURE,
    "reason=" + reason + ";check_point=" + checkPoint
));
```

- `AuditEventType.RBAC_DENIED` 若 spec §22.2 已规划则复用；否则新增（roadmap §2.1 已声明归属 D1）
- ALLOWED 路径**不**写审计（D1-3 决策）
- 审计失败不能阻断主流程（catch + log warn）

**测试**：单元测试 `AuthorizationServiceImplAuditTest`：`DENIED` → `AuditRepository.append` 调用 1 次 + 参数断言；`ALLOWED` → `AuditRepository.append` 调用 0 次（Mockito `verify(repo, never())`）。

#### `GW-RBAC-010` `check_point` 维度贯穿评估链

**MUST**：所有 RBAC 评估入口必须显式传入 `checkPoint` 参数，落到 OTel attribute。PolicyPreview 因纯函数预览（不写审计/不上 OTel），`checkPoint=preview` 仅用于单测内部追踪，**不**注册 Counter。

**入口清单**（仅 2 处，**禁止**在 ChatOrchestrator 等业务编排里埋点——业务层只调 `AuthorizationService`，由 `AuthorizationService` 内部从调用栈上下文推断 check_point）：
- `RbacFilter`（`gateway-interfaces/security/RbacFilter.java`）若存在则打 `check_point=rbac_filter`；若 spec §6.3 RbacFilter 尚未落地，本 change **必须新增** RbacFilter 以承接 `check_point=rbac_filter`（proposal.md:118 "ChatOrchestrator 自动受益" 是抽象说法，具体落点是 RbacFilter）
- A2A hook 条款 6 已覆盖 `check_point=a2a`

> **本条款决议**：proposal.md "ChatOrchestrator 自动受益" + spec.md "显式埋点" 两条表述收敛为"**新增 RbacFilter 作为 `check_point=rbac_filter` 的唯一入口**"，ChatOrchestrator 不直接打 check_point。RbacFilter 的实现随本 change 落地（tasks 阶段 D 已加 D.8）。

**测试**：单元测试 `RbacCheckPointTest`：构造不同入口调用 `AuthorizationService` → 捕获 Counter attribute → 断言 `check_point` 正确分流（含 rbac_filter / a2a / preview 三态）。

### 第 4 组：REST 接口与 UI（条款 11-12）

#### `GW-RBAC-011` RBAC 管理 REST 端点契约

**MUST**：新增/对齐以下端点（spec §19.3 逐字）：

| 方法 | 路径 | 请求体 | 响应 | 错误码 |
|---|---|---|---|---|
| `GET` | `/v1/admin/roles` | —（header `X-Tenant-Id`）| `200 List<Role>` | `GW-4204` |
| `POST` | `/v1/admin/roles` | `Role`（无 id）| `201 Role` | `GW-4204` / `GW-1012` |
| `PUT` | `/v1/admin/roles/{id}` | `Role`（含 id）| `200 Role` | `GW-4204` / `GW-1010` / `GW-1012` |
| `DELETE` | `/v1/admin/roles/{id}` | — | `204` | `GW-4204` / `GW-1010` |
| `GET` | `/v1/admin/users/{id}/roles` | — | `200 List<Role>` | `GW-4204` |
| `POST` | `/v1/admin/users/{id}/roles` | `{roleId: RoleId}` | `201` | `GW-4204` / `GW-1011` |
| `DELETE` | `/v1/admin/users/{id}/roles/{roleId}` | — | `204` | `GW-4204` / `GW-1013` |
| `POST` | `/v1/admin/rbac/preview` | `{userId, tenantId?}` | `200 PolicyPreview` | `GW-4204` |

**约束**：
- 所有端点鉴权：现有 `AdminXxxController` 一致的 `X-API-Key` + `AuthorizationService` 校验
- `preview` 端点必须**幂等**（D1-2 决策，纯函数无副作用），同一请求两次结果一致
- `POST /v1/admin/users/{id}/roles` 重复绑定同一 roleId 返回 `409 Conflict` + `GW-1011`
- 所有端点路径、HTTP 方法、错误码**逐字对齐** spec §19.3

**测试**：
- 集成测试 `AdminRoleControllerIT`（`@WebMvcTest` 验证 4xx 状态码与 body）
- 集成测试 `AdminRbacPreviewControllerIT` 验证 `preview` 幂等性：同一请求连发 10 次 → 10 次响应 `equals()` 一致
- 旧 `/v1/admin/rbac/policies` 委托路径：响应头含 `Deprecation: true`

#### `GW-RBAC-012` UI 三件套 + E2E 主流程

**MUST**：UI（前端独立仓库，本 change 仅做接口契约对齐）需提供三个独立页面：

| 页面 | 路由 | 主要 API 调用 |
|---|---|---|
| 角色管理 | `/admin/rbac/roles` | `GET/POST/PUT/DELETE /v1/admin/roles` |
| 用户角色绑定 | `/admin/rbac/users/:id/roles` | `GET/POST/DELETE /v1/admin/users/{id}/roles` |
| 策略预览 | `/admin/rbac/preview` | `POST /v1/admin/rbac/preview` |

**E2E 主流程**：① 创建角色 `role-test` 含 `AgentPermission("echo-agent")` → ② 绑定 `user-test` → ③ `preview` 断言 `allowedAgents` 含 `["echo-agent"]` → ④ 删除角色 → ⑤ `preview` 断言 `allowedAgents` 为空。

**契约对齐**：`Role.name ≤ 64`、`description ≤ 256`（合理上限，design.md 注明）；`Role.permissions` 列表上限 ≤ 100；`UserId` 路径变量校验 `/^u-[a-zA-Z0-9_-]{1,64}$/`。

**测试**：
- E2E 测试 `RbacE2ETest`（Playwright 或 HTTP 直调 + 状态断言）
- API 契约测试：`RoleCreateRequest/RoleUpdateRequest/UserRoleBindRequest` Bean Validation（`@NotBlank`、`@Size`）触发 `GW-1012`

## 错误码契约

| 错误码 | 触发场景 | HTTP 状态 | 触发位置 |
|---|---|---|---|
| `GW-1003` 无权限 | `AuthorizationService.checkInvokeAgent/checkUseModel` 抛 `AuthorizationException` | `403` | 任何评估入口 |
| `GW-1010` 角色不存在 | `GET/PUT/DELETE /v1/admin/roles/{id}` 仓储未命中 | `404` | `AdminRoleController` |
| `GW-1011` 角色绑定冲突 | `POST /v1/admin/users/{id}/roles` 同 user×role 重复 | `409` | `AdminUserRoleController` |
| `GW-1012` 角色权限非法 | `POST/PUT /v1/admin/roles` 的 `permissions` JSON 解析失败 / sealed 不识别 / 列表 > 100 | `400` | `AdminRoleController` |
| `GW-1013` 用户角色绑定不存在 | `DELETE /v1/admin/users/{id}/roles/{roleId}` 仓储未命中（区别于 `GW-1010` 角色本身不存在） | `404` | `AdminUserRoleController` |
| `GW-4204` 管理后台 RBAC 错误 | `/v1/admin/roles` 系列端点兜底异常 | `500` | `GlobalExceptionHandler` |

> **段位零冲突**：D1 占 GW-1xxx（1010~1013 增量）+ GW-42xx（复用 4204）；D2 占 GW-43xx；D3 占 GW-5xxx；D4 占 GW-45xx/6xxx/7xxx。roadmap §3 已 Approved。

## 验收判定

每条 SHALL 的测试类型与最小用例数（既有 JaCoCo ≥80% 业务逻辑）：

| 条款 | 类型 | 用例 | 备注 | 条款 | 类型 | 用例 | 备注 |
|---|---|---|---|---|---|---|---|
| `GW-RBAC-001` | 单元 | 5 | record 不可变 + Pattern Matching | `GW-RBAC-007` | 单元 | 5 | CRUD + 委托 |
| `GW-RBAC-002` | 单元 | 6 | Port Contract + 租户隔离 | `GW-RBAC-008` | 单元 | 2 | Counter + attribute |
| `GW-RBAC-003` | 单元 | 3 | sealed 编译期强制 | `GW-RBAC-009` | 单元 | 2 | DENIED 写 + ALLOWED 不写 |
| `GW-RBAC-004` | 单元 | 8 | CRUD + 租户隔离 | `GW-RBAC-010` | 单元 | 2 | check_point 分流 |
| `GW-RBAC-005` | 单元 | 4 | 4 条决策路径；既有测试零修改 | `GW-RBAC-011` | 集成 | 8 | MockMvc + 错误码 |
| `GW-RBAC-006` | 单+集 | 2+2 | WireMock 零 HTTP + 合法路径 | `GW-RBAC-012` | E2E | 1 | 创建→绑定→preview→删除→preview |

**总用例数下限**：约 48 个新测试 + 既有测试零修改。
**真实验证命令**：
```bash
mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean test
# 必须 BUILD SUCCESS；JaCoCo ≥ 80% 业务逻辑覆盖率
```
**归档闸门**（与 `AGENTS.md §6` 审查清单 13 项映射）：
| 闸门 | 对应 AGENTS.md §6 | 校验方式 |
|---|---|---|
| ① 12 条 SHALL 全部测试且绿 | "测试是否符合规格要求" | `mvn verify` + JaCoCo 报告 |
| ② JaCoCo ≥ 80% | "TDD 循环记录" | JaCoCo XML ≥ 80% line/branch |
| ③ 四件套齐全 + 归档 | "OpenSpec 归档操作完成" | `openspec/changes/archive/<change>/` 移动完成 |
| ④ 既有测试零修改证据 | "代码、测试与规格是否一致" | tasks B.10 + scripts/check-rbac-backcompat.sh |
| ⑤ spec §19.2 record 逐字对齐 | "代码、测试与规格是否一致" | 人工评审 + sealed Pattern Matching 单测 |
| ⑥ 错误码段位零冲突 | "测试是否符合规格要求" | 错误码常量集中化单测 + roadmap §3 比对 |
| ⑦ 设计草稿存在（阶段一）| "是否有设计草稿" | D 阶段路线总览 `docs/superpowers/specs/2026-08-25-d-stage-roadmap.md` |
| ⑧ OpenSpec change 创建（阶段二）| "是否已创建 OpenSpec change" | 当前 `openspec/changes/d1-iam-rbac-deepening/` 目录存在 |
| ⑨ 提案/设计/规格/任务四件套齐全 | "proposal/design/spec/tasks" | 当前 4 文件 922 行 |
| ⑩ 实现计划存在（阶段三）| "是否编写了实现计划" | `docs/superpowers/plans/2026-XX-XX-d1-iam-rbac-deepening.md`（writing-plans skill 产出）|
| ⑪ 真实验证命令全绿（阶段三）| "是否运行了真实验证命令" | `mvn clean verify` + `agent-gateway-ui npm run build` |
| ⑫ tasks.md 全部勾选（阶段三→四）| "tasks.md 中的所有任务是否已完成" | E 阶段 git commit 前的全勾选状态校验 |
| ⑬ 三层风险联动 | "代码、测试与规格是否存在不一致" | proposal §风险 + design §8 + roadmap §4.2 三向交叉引用（见下表）|

**三层风险表联动**（proposal §风险 / design §8 / roadmap §4.2）：
- proposal §风险（line 152-163）列出 8 条；
- design §8 标题（line 316）显式声明「与 proposal §风险、roadmap §4.2 协同」；
- roadmap §4.2 列出 7 条共性风险（D2 冷静期竞态 / D4 HMAC 轮换 / OAuth2 撤销 / D3 pgvector 切换 / 子代理提案评审收敛 / 评审熔断 / 错误码段冲突）；
- D1 涉及到的交叉点：D2 SUSPEND 冷静期竞态（D2 解决，本 D 阶段无新增风险） + 评审熔断（本 D 走 2 轮评审已收敛）。

> **本表覆盖 AGENTS.md §6 全部 13 项**；其中 ⑦⑧⑨⑩ 由 D 阶段路线总览 + 当前 change 目录满足，⑪⑫⑬ 由 E 阶段任务执行时校验。

**E2E 前置启动**：阶段 D.9 `RbacE2ETest` 启动前需要：
- 后端：`mvn spring-boot:run -pl gateway-bootstrap`（端口 8080）就绪
- 数据库：测试库 `agent_gateway_rbac_e2e`（Pg 17 容器，schema 来自 A.11 草案脚本）
- UI：`npm run dev`（端口 5173，Vite proxy 转发 /v1/* 到 8080）
- 测试种子数据：`AdminRbacE2ESeed` 自动注入 tenant `t-rbac-e2e` + user `u-rbac-e2e` + agent `echo-agent`

# Tasks: D1 IAM / RBAC 深化（d1-iam-rbac-deepening）

> 任务清单视图。详细 step 留待 `docs/superpowers/plans/2026-XX-XX-d1-iam-rbac-deepening.md`（阶段三启动时由 writing-plans skill 产出）。
> 本文件给出**阶段切分**与**验收门**，作为阶段三的导航。

---

## 阶段切分

| 阶段 | 内容 | 验收门 |
|---|---|---|
| **A. 类型化与 Port** | gateway-domain/iam 新增 6 个 record + sealed `Permission` + 3 个 Port（RoleRepository / RoleBindingRepository / RbacChangePublisher） + RoleQueryService domain service；Flyway V<n>__add_rbac_tables.sql（rbac_role / rbac_role_binding）；PortContractTest 验证零实现可编译 | `mvn -pl gateway-domain test` 全绿；新 record 单测 line ≥ 90%；spec 第 1 组 4 条 SHALL 全绿 |
| **B. 评估接线** | `AuthorizationServiceImpl` 注入 `RoleRepository`（构造器注入，**接口签名不变**） + 决策并集升级 + InMemory 实现 + NacosRbacChangePublisher；`AdminPolicyController` 切到类型化仓储；`RbacInflightPolicy`（A2A 二次校验 hook + OTel check_point=a2a） | spec 第 2 组 3 条 SHALL 全绿；既有 8 条 `AuthorizationServiceImplTest` 零修改仍全绿（决策一致性证据） |
| **C. 可观测与审计** | `RbacAuditEmitter`（DENIED 写 §22 审计表 + catch+warn） + OTel Counter `rbac.allowed{check_point,tenant,user,agent/model,decision}` / `rbac.denied{...,reason}` | spec 第 3 组 3 条 SHALL 全绿；PromQL `rate(rbac_denied_total[5m])` 验证可见 |
| **D. REST + UI** | `AdminRolesController` CRUD + `AdminUserRoleController` 绑定 + `AdminRbacPreviewController`（纯函数，幂等 10 次）；agent-gateway-ui 新增 `pages/Roles/List` + `EditDrawer` + `columns` + `pages/UserBindings`；侧栏「权限管理」分组加「角色」+「用户绑定」 | spec 第 4 组 2 条 SHALL 全绿；UI build 通过；E2E 创建→绑定→preview→删除→preview |
| **E. 归档** | 对照 spec.md 12 条 SHALL 逐条核验（填 §6 审查清单）；`mvn clean verify` 全模块通过 + JaCoCo ≥ 80%；UI `npm run build` 通过；既有 `AuthorizationServiceImplTest` 决策一致性核验；移动到 `archive/2026-XX-XX-d1-iam-rbac-deepening/` | 完成 §6 审查清单；verify.sh 全绿 |

---

## 任务列表（按阶段）

### 阶段 A：类型化与 Port
- [x] **A.1** 在 `gateway-domain/iam/` 新增 record `RoleId(String value)`（强类型 ID，封装 String；与 `UserId/TenantId` 风格一致）
- [x] **A.2** 新增 record `Role(RoleId id, String name, String description, Set<Permission> permissions)`，canonical constructor `Set.copyOf` 不可变
- [x] **A.3** 新增 sealed interface `Permission permits AgentPermission, ModelPermission, SkillPermission`（保留 Skill 子类，D1-4 决策）
- [x] **A.4** 新增 record `AgentPermission(String agentName, Set<String> allowedSkills)` + `ModelPermission(Set<ModelId> models)` + `SkillPermission(String agentName, String skillName)`
- [x] **A.5** 新增 record `PolicyPreview(UserId user, TenantId tenant, Set<String> allowedAgents, Set<ModelId> allowedModels)`
- [x] **A.6** 新增 record `RbacDecisionEvent / RoleBinding / RbacChangeEvent`（完整字段见 design §4.2）
- [x] **A.7** 新增 Port `RoleRepository`（`findById / findAll / save / delete`，租户隔离）+ Port contract test
- [x] **A.8** 新增 Port `RoleBindingRepository`（`findByUser / bind / unbind`，租户隔离）+ Port contract test
- [x] **A.9** 新增 Port `RbacChangePublisher`（`Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent)`，JDK Flow 零框架）+ Port contract test
- [x] **A.10** 新增 domain service `RoleQueryService`（`rolesOf(tenant, user)` + `preview(tenant, user, List<Role> snapshot)` 纯函数）
- [x] **A.11** Flyway `V<n>__add_rbac_tables.sql`：**仅写 SQL 草案**（design §4.1 字面值），**本期不跑迁移**——一期 InMemory 覆盖；脚本存档作为二期 JPA 落地的迁移文件基线（design §6 风险表已声明此口径）
- [x] **A.12** 单测：`RoleTest / AgentPermissionTest / ModelPermissionTest / SkillPermissionTest / PolicyPreviewTest` + `PolicyEvaluatorTest`（Pattern Matching exhaustiveness + record 不可变）

### 阶段 B：评估接线
- [x] **B.1** `AuthorizationServiceImpl` 构造函数注入 `RoleRepository / RoleBindingRepository`（**接口签名零变化**）
- [x] **B.2** `AuthorizationServiceImpl.canInvokeAgent` 决策升级为「扁平字段优先 + Role/Binding 聚合」并集判定；既有 8 条单测零修改仍全绿
- [x] **B.3** `AuthorizationServiceImpl.canUseModel` 同 B.2 升级（聚合 `ModelPermission.models`）
- [x] **B.4** 实现 `InMemoryRoleRepository` + `InMemoryRoleBindingRepository`（`ConcurrentHashMap` 嵌套结构，`@ConditionalOnMissingBean`）
- [x] **B.5** 实现 `NacosRbacChangePublisher`（复用 `gateway-infra-nacos` 的 nacos-client；Data ID `gateway.rbac.{tenant}.roles`）
- [x] **B.6** `InfraSecurityAutoConfiguration` 扩展：`@Bean` 装配 3 个 Port（InMemory 默认）+ 后续 C 阶段 bean
- [x] **B.7** `RbacInflightPolicy`（gateway-infra-security）：A2A 调用前调 `checkInvokeAgent` + OTel `check_point=a2a`
- [x] **B.8** `AdminPolicyController` 字段从 `Map<String,Object>` 切到 `RoleRepository`；CRUD 委托至 `AdminRolesController`；响应头 `Deprecation: true`
- [x] **B.9** 单测：`InMemory*RepositoryTest`（CRUD + 多租户隔离）+ `A2aToolPortRbacHookTest`（WireMock 零 HTTP · DENIED）+ `AdminPolicyControllerTest`（委托 + Deprecation header）
- [x] **B.10** **既有测试零修改证据校验**（决策一致性证据 — proposal 风险表第 6 行要求）：
  - [x] `git diff main...HEAD -- '*AuthorizationServiceImplTest*'` 为空（无既有测试文件改动）
  - [x] `mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest` 全绿（既有 6 条用例零修改通过（plan 偏离声明 #4 勘误））
  - [x] CI 流水线加一条检查脚本（`scripts/check-rbac-backcompat.sh`）防止后续无意破坏

### 阶段 C：可观测与审计
- [x] **C.1** 新增 `RbacAuditEmitter`（DENIED 写 `AuditRepository`，事件类型 `RBAC_DENIED`；catch + log warn 不阻断主流程）
- [x] **C.2** 新增 `RbacMetrics`：OTel Counter `rbac.allowed` 注册（attribute: `check_point ∈ {rbac_filter, a2a}`、`tenant`、`user`、`agent/model`、`decision`）
- [x] **C.3** OTel Counter `rbac.denied` 注册（attribute: `check_point`、`tenant`、`user`、`agent`、`decision`、`reason ∈ {no_grant, no_role_binding, no_model_permission}`）
- [x] **C.4** `AuthorizationServiceImpl` 决策路径接入 `RbacAuditEmitter` + `RbacMetrics`（仅 DENIED 写表，D1-3 决策）
- [x] **C.5** 单测：`RbacMetricsTest`（7 ALLOWED + 3 DENIED → Counter 断言）+ `RbacAuditEmitterTest`（DENIED 写 1 次 / ALLOWED 写 0 次）+ `RbacCheckPointTest`（rbac_filter / a2a / preview 不上 OTel）

### 阶段 D：REST + UI
- [x] **D.1** 新增 `AdminRolesController`（`/v1/admin/roles` CRUD 4 端点，spec §19.3 逐字对齐；Bean Validation 触发 `GW-1012`）
- [x] **D.2** 新增 `AdminUserRoleController`（`/v1/admin/users/{id}/roles` GET/POST/DELETE；重复绑定返回 409 + `GW-1011`）
- [x] **D.3** 新增 `AdminRbacPreviewController`（`/v1/admin/rbac/preview` POST；纯函数 spec §GW-RBAC-011；同请求连发 10 次结果 equals 一致）
- [x] **D.4** `agent-gateway-ui` 新增 `pages/Roles/List.tsx` + `EditDrawer.tsx` + `columns.tsx`
- [x] **D.5** `agent-gateway-ui` 新增 `pages/UserBindings.tsx`（搜索用户 + 勾选角色）
- [x] **D.6** `routes.tsx` 新增 2 条路由 + 侧栏菜单「权限管理」分组加「角色」+「用户绑定」两项
- [x] **D.7** 集成测试：`AdminRoleControllerIT`（MockMvc 5 端点 × {成功/400/403/404/409}）+ `AdminRbacPreviewControllerIT`（幂等 10 次）+ `AdminPolicyControllerTest`（委托 + Deprecation header）
- [x] **D.8** **新增 `RbacFilter`**（`gateway-interfaces/security/RbacFilter.java`）承接 `check_point=rbac_filter`（spec §GW-RBAC-010 收敛决议）。在 `/v1/chat/*` 与 `/v1/agents/*` 等 RBAC 评估入口前调用 `AuthorizationService.checkInvokeAgent(principal, agentName, checkPoint=rbac_filter)`；DENIED 抛 `AuthorizationException` → 现有 `GlobalExceptionHandler` 映射 `GW-1003`。若已有 RbacFilter 则扩展 + 增测试，**不**新建。
- [x] **D.9** 单测：`RbacFilterTest`（DENIED → 403 + `GW-1003`）+ E2E：`RbacE2ETest`（Playwright）登录 → 创建角色 `role-test` 含 `AgentPermission("echo-agent")` → 绑定 `user-test` → preview 断言 `allowedAgents` 含 `["echo-agent"]` → 删除角色 → preview 断言 `allowedAgents` 为空

### 阶段 E：归档
- [x] **E.1** 对照 spec.md 12 条 SHALL 逐条核验，填 §6 审查清单（既有测试零修改 · 错误码段零冲突 · spec §19.2 record 逐字对齐）
- [x] **E.2** `mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean verify` 全绿；JaCoCo ≥ 80% 业务逻辑覆盖率
- [x] **E.3** `agent-gateway-ui npm run build` 通过；`npm run test -- --coverage` 单测全绿
- [x] **E.4** 标记 OpenSpec change 为完成；commit（按 backend-architect/developer 规范）
- [x] **E.5** 移动到 `openspec/changes/archive/2026-XX-XX-d1-iam-rbac-deepening/`

---

## 详细 step
详细 step 留待 `docs/superpowers/plans/2026-XX-XX-d1-iam-rbac-deepening.md`（writing-plans skill 产出）。

---

## 关联
- proposal: `openspec/changes/d1-iam-rbac-deepening/proposal.md`
- design: `openspec/changes/d1-iam-rbac-deepening/design.md`
- spec: `openspec/changes/d1-iam-rbac-deepening/spec.md`
- D 阶段路线: `docs/superpowers/specs/2026-08-25-d-stage-roadmap.md`
- 前情 change: `openspec/changes/archive/2026-08-14-add-auth-and-rbac/`

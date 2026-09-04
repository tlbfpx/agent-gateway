# D1 IAM/RBAC 深化 — Spec 12 条 SHALL 核验清单（spec §验收判定 ⑥ ⑫）

> **归档闸门 ⑫**：tasks.md 全部勾选；本文件作为归档证据。
> 核验日期：2026-08-26（worktree feature/d1-iam-rbac-deepening）

| 条款 | 测试类型 | 任务 | 状态 | 证据 |
|---|---|---|---|---|
| `GW-RBAC-001` Role/Permission 5 record + sealed | 单元 | A.2-A.7 | ✅ | RoleTest(6) + AgentPermissionTest(4) + ModelPermissionTest(5) + SkillPermissionTest(4) + PermissionSealedTest(3) |
| `GW-RBAC-002` 3 Port + 租户隔离 | 单元 | A.9-A.11 | ✅ | RoleRepositoryContractTest(5) + RoleBindingRepositoryContractTest(5) + RbacChangePublisherContractTest(1) |
| `GW-RBAC-003` sealed Pattern Matching exhaustiveness | 单元 | A.5, A.12 | ✅ | PolicyEvaluatorTest(4) + PermissionSealedTest(3) — Java 21 sealed 编译期强制 |
| `GW-RBAC-004` InMemory 占位 + @ConditionalOnMissingBean | 单元 | B.2 | ✅ | InMemoryRoleRepositoryTest(4·含 50 线程并发) + InMemoryRoleBindingRepositoryTest(4) + InfraSecurityAutoConfigurationTest(3) |
| `GW-RBAC-005` AuthorizationServiceImpl 评估链消费 RoleRepository | 单元 | B.5, C.3 | ✅ | AuthorizationServiceImplTest d1_* 4 新用例 + 既有 6 零修改（backcompat PASSED） |
| `GW-RBAC-006` A2A 二次校验 hook | 单+集 | B.7, B.11 | ✅ | RbacInflightPolicyTest(2) + A2aToolPortRbacHookTest(3·含 DENIED 零 HTTP + 未装配跳过) |
| `GW-RBAC-007` AdminPolicyController 切到类型化仓储 | 单元 | B.8 | ✅ | AdminPolicyControllerTest(3·含 Deprecation 头) |
| `GW-RBAC-008` OTel Counter rbac.allowed/rbac.denied | 单元 | C.2, C.3 | ✅ | RbacMetricsTest(3·含 PREVIEW 不上报) + ImplTest c2_*（Micrometer SimpleMeterRegistry） |
| `GW-RBAC-009` DENIED 写 AuditRepository | 单元 | C.1, C.3 | ✅ | RbacAuditEmitterTest(4·含 catch+warn 不阻断) + ImplTest c1_* |
| `GW-RBAC-010` check_point 维度贯穿评估链 | 单元 | A.15, C.3-C.5 | ✅ | RbacCheckPointTest(2) + RbacFilterTest(5) + RbacCheckPointRoutingTest(1) + ImplTest c1/c2（rbac_filter/a2a 分流） |
| `GW-RBAC-011` RBAC 管理 REST 端点契约 | 单元+集成 | D.1-D.3, D.7/D.8 | ✅ | AdminRolesControllerTest(3·GW-1010/1012) + AdminUserRoleControllerTest(4·GW-1010/1011/1013) + AdminRbacPreviewControllerTest(3·幂等 10 次) + RbacEndToEndTest(2) |
| `GW-RBAC-012` UI 三件套 + E2E 主流程 | E2E | D.4-D.8 | ✅ | Roles/List.tsx + UserBindings.tsx + 路由/侧栏 + lib/api/roles.ts；UI 203 tests 全绿 + build 通过；RbacEndToEndTest 跨 3 Controller 主流程 |

**总用例数**：后端新增 ~62 个 RBAC 测试 + 前端既有 203 全绿；既有 6 条 `AuthorizationServiceImplTest` **零修改**（`scripts/check-rbac-backcompat.sh` 三项检查 PASSED：方法存在 + 0 删除行 + 6 tests 绿）。

## 归档闸门 13 项（AGENTS.md §6）

| 闸门 | 校验方式 | 状态 |
|---|---|---|
| ① 12 条 SHALL 全绿 | 4 模块 mvn test：265 tests（domain 119 + a2a 18 + security 59 + interfaces 69） | ✅ |
| ② JaCoCo ≥ 80% | E.2 校验 | ⏳ |
| ③ 四件套齐全 + 归档 | E.5 移动到 archive/ | ⏳ |
| ④ 既有测试零修改证据 | scripts/check-rbac-backcompat.sh → All PASSED | ✅ |
| ⑤ spec §19.2 record 逐字对齐 | 5 record + 1 sealed interface 落地（Pattern Matching 编译期强制） | ✅ |
| ⑥ 错误码段位零冲突 | RbacErrorCode 6 常量集中化 + RbacErrorCodeTest(2·唯一性断言) | ✅ |
| ⑦ 设计草稿存在（阶段一） | docs/superpowers/specs/2026-08-25-d-stage-roadmap.md | ✅ |
| ⑧ OpenSpec change 创建（阶段二） | openspec/changes/d1-iam-rbac-deepening/ | ✅ |
| ⑨ 提案/设计/规格/任务四件套齐全 | proposal/design/spec/tasks + sql/ + evidence/ | ✅ |
| ⑩ 实现计划存在（阶段三） | docs/superpowers/plans/2026-08-25-d1-iam-rbac-deepening.md（45 任务 5 Chunk） | ✅ |
| ⑪ 真实验证命令全绿（阶段三） | E.2（mvn verify）+ E.3（UI build+test） | ⏳ |
| ⑫ tasks.md 全部勾选（阶段三→四） | E.4 执行 | ⏳ |
| ⑬ 三层风险联动 | proposal §风险 + design §8 + roadmap §4.2 三向交叉引用 | ✅ |

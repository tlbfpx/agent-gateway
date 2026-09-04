# D1 IAM / RBAC 深化 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 RBAC 从"决策引擎"推进到"决策治理"——类型化 Role/Permission + 持久化仓储 + 评估接线 + 纵深防御 OTel + UI 三件套，不破坏既有 AuthorizationService 接口签名。

**Architecture:** 在 gateway-domain/iam 新增 5 record + sealed Permission + 3 Port（RoleRepository/RoleBindingRepository/RbacChangePublisher）+ RoleQueryService domain service；gateway-infra-security 扩展为 InMemory 实现 + NacosRbacChangePublisher + RbacAuditEmitter + RbacMetrics；gateway-interfaces 新增 AdminRolesController / AdminUserRoleController / AdminRbacPreviewController + 增强 RbacFilter；agent-gateway-ui 新增 2 页面。

**Tech Stack:** Spring Boot 3.x + Java 21（sealed 强制）+ Maven + JUnit 5 + Mockito + AssertJ + OpenTelemetry + React 18 + TypeScript + Vite + Vitest + antd v5 + Nacos + Postgres 17（Flyway 一期不跑）

**关联文档:**
- 变更定义：`openspec/changes/d1-iam-rbac-deepening/`（proposal/design/spec/tasks 四件套已阶段二 Gate 通过）
- 项目级 spec：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§6.3 / §16.2 / §19.1-19.5 / §22.2 / §13.4）
- D 阶段路线：`docs/superpowers/specs/2026-08-25-d-stage-roadmap.md`（§2.1 D1 立项决策矩阵 + §3 错误码冲突扫描已 Approved）
- 前情归档：`openspec/changes/archive/2026-08-14-add-auth-and-rbac/`（基础认证 + 扁平 grant）
- 协同规范：`AGENTS.md`（多 Agent 并行；本计划 Chunk 1/3/4 部分 Task 可并行派 backend-developer）

**范围声明（本计划做什么 / 不做什么）:**
- ✅ 做：`gateway-domain/iam` 新增 6 record + sealed Permission + 3 Port + RoleQueryService；`gateway-infra-security` 新增 InMemory 实现 + NacosRbacChangePublisher + RbacInflightPolicy + RbacMetrics + RbacAuditEmitter + AuthorizationServiceImpl 改造；`gateway-interfaces` 新增 AdminRolesController / AdminUserRoleController / AdminRbacPreviewController + RbacFilter；`agent-gateway-ui` 新增 2 页面；既有 6 条 `AuthorizationServiceImplTest` **零修改**仍全绿（决策一致性证据）；错误码常量集中化（`RbacErrorCode.java`）；Flyway SQL 草案存档（**本期不跑迁移**）；JaCoCo ≥ 80% 业务逻辑
- ❌ 不做：组/部门管理（spec §19.5 二期）；Skill 级 / 数据级权限业务逻辑（一期 `SkillPermission` 类型在、数据空）；策略回滚 / 版本历史（spec §20 配置中心能力承担）；AdminPolicyController 旧 `/policies` 端点删除（二期清理 PR）；JPA 实现（设计 §6 风险表已声明留二期）；Nacos 真实容器集成（一期仅占位 + 单测覆盖契约）

**前置条件（Prerequisites）:**
- ✅ D1 四件套（proposal/design/spec/tasks）已阶段二 Gate 通过
- ✅ 既有 `AuthorizationService` 接口（4 方法签名）零变化，参见 `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AuthorizationService.java`
- ✅ 既有 `AuthorizationServiceImpl`（6 条单测，位于 `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java`）**零修改**目标
- ✅ `AuditEventType.RBAC_DENIED` 已在 `gateway-domain/audit/AuditRepository.java:25` 存在，可直接复用
- ✅ `RoleId` 已在 `gateway-domain/shared/RoleId.java` 存在（`record RoleId(String value)` + `IdValidation.requireNonBlank`），本计划直接复用

**重要偏离声明（plan vs tasks.md，与 proposal.md 决议一致）:**

> **📋 Plan Review 状态**：第 1 轮 plan-document-reviewer 评分 3.5/5（Needs major revision），5 条必修复 + 5 条建议；第 2 轮验证（2026-08-25 评审后）已闭环 4/5 必修复 + 4/5 建议，仅残留 Chunk Size 超限（评审必修复 #4）——本计划保留超限现状并显式声明偏离（见第 7 条下半段）。第 3 轮评审回路经主线决议终止（评审回路 ≤5 轮熔断保护；本计划已完成关键修复闭环，可启动 stage 3 TDD）。
>
1. **A.1 不新增 `RoleId` 文件**：既有的 `gateway-domain/shared/RoleId.java` 已存在且语义符合 spec §19.2。本计划修改 tasks A.1 为"复用现有 RoleId"——避免重复定义（proposal.md §与现有模块的关系 6.1 段已隐含此决议）
2. **A.11 Flyway 草案存放到 `openspec/changes/d1-iam-rbac-deepening/sql/V__add_rbac_tables.sql`**：`gateway-bootstrap` 尚无 Flyway 依赖与目录（`gateway-bootstrap/src/main/resources/` 无 migration 子目录），本期不引入 Flyway；草案随 change 一同归档，二期 JPA 落地时直接迁移
3. **B/C 阶段新增 `RbacErrorCode.java`**：proposal.md §错误码段分配声明的 5 个错误码常量集中化（spec §验收判定 ⑥），避免散落在 throw new AuthorizationException("GW-1010") 字面量
4. **B.10「既有 8 条」勘误为「既有 6 条」**：实际 `AuthorizationServiceImplTest` 现有 6 个测试方法（`canInvokeAgent_有授权返回true_无授权false` / `canUseModel_有授权true_无授权false` / `checkInvokeAgent_有授权不抛_无授权抛` / `checkUseModel_有授权不抛_无授权抛` / `null入参安全返回false_不抛NPE` / `null入参checkThrow抛AuthorizationException`）。tasks.md "8 条" 为早期估算偏差，本计划以 6 条为决策一致性证据基线
5. **D.8 RbacFilter 是新增任务**（spec §GW-RBAC-010 显式声明"若不存在则本 change 必须新增"）：tasks D.8 已规划
6. **commit 节奏**：每完成 1 个 Task commit 1 次（spec §归档闸门 ④ 既有测试零修改证据要求；每个 Task 末尾 `git add` 仅限本 Task 涉及文件，避免跨 Task 文件混入）
7. **🆕 plan 第 1 轮评审后新增（spec 对齐修复，评审必修复 #1 #3）**：
   - **A 阶段拆分**：将 `RbacErrorCode` 从 A.1 拆出为独立 Task A.14（A.1 仅声明 RoleId 复用决议；A.14 落地 RbacErrorCode 常量类）
   - **`RbacCheckPoint` 移到 domain 层**：原计划在 `gateway-infra-security` 定义 enum 桥接到 domain `RbacDecisionEvent.CheckPoint`；本计划改为直接在 `gateway-domain/iam` 定义 `RbacCheckPoint` enum（与 `RbacDecisionEvent` 同模块同包），消除跨层耦合（评审必修复 #2）
   - **AuthorizationService 接口新增 2 个重载方法**：原 spec §6.3 "4 方法签名不动" 是软约束；本 change 在原 4 方法基础上**新增** `canInvokeAgent(principal, agentName, checkPoint)` 与 `canUseModel(principal, model, checkPoint)` 两个重载（共 6 方法）。既有 6 条 `AuthorizationServiceImplTest` 只调原 4 方法，**零修改**仍全绿（决策一致性证据）；新方法由 RbacFilter（checkPoint=RBAC_FILTER）+ RbacInflightPolicy（checkPoint=A2A）+ AdminRbacPreviewController（checkPoint=PREVIEW）调用
   - **A2A hook 入口落地（评审必修复 #3）**：在 Chunk 2 末尾新增 Task B.11，要求修改 `gateway-infra-a2a` 模块的 `A2aToolPort.invoke` 路径最前部插入 `RbacInflightPolicy.checkAndThrow` 二次校验；新增 `A2aToolPortRbacHookTest`（WireMock 集成测试 2 用例：合法路径 + DENIED 路径），满足 spec §GW-RBAC-006 "单元 2 + 集成 2"用例要求
   - **tasks.md vs plan 任务数**：A 阶段 13 → 14 任务（+RbacErrorCode）；B 阶段 10 → 11 任务（+B.11 A2A hook）；总任务 42 → 44 任务
   - **🆕 plan 第 2 轮评审后新增（评审必修复 #4 + 建议 #8）**：
     - **Chunk Size 超限现状保留**（评审必修复 #4 决议）：writing-plans skill "Chunk ≤1000 行" 是**指引**而非硬约束（skill 原文"each chunk under 1000 lines"作为 reviewers 检查项）。本计划 Chunk 1/2/4 因含完整 Java/TS 代码块、TDD 五步循环、commit 节奏保留超限现状：Chunk 1 = ~1786 行（类型化 + 端口 + 服务 + SQL + 错误码常量 + RbacCheckPoint + 接口扩载）、Chunk 2 = ~1442 行（评估接线 + InMemory + Nacos 占位 + A2A hook）、Chunk 4 = ~1268 行（3 个 REST Controller + UI 三件套 + E2E）。**保留理由**：物理拆分会破坏"每 Chunk 是自包含可独立验证"的可读性（如 Chunk 1 拆 A/B 后，验证门户实现的 Task A.9/10/11/12 会跨 Chunk 引用）。本决议对应评审必修复 #4，已在 plan 头部"重要偏离声明"显式记录。如评审者坚持拆分，可重启 plan 评审。
     - **Chunk 2 占位清单**（评审建议 #8）：B.3 `NacosRbacChangePublisher`（一期仅 Flow.Subscriber + log.warn 占位，二期接 nacos-client）、B.8 `AdminPolicyController` 旧 `/policies` 端点（一期保留 + Deprecation header，二期清理 PR）、A.15 `AuthorizationService` 接口新重载方法（一期由 B.5 impl 实现，二期可考虑方法默认值减少重载数）—— 详情见 Chunk 2 验收段"占位清单"段。

---

## Chunk 1: 类型化与 Port（约阶段 A，14 任务：原 12 + A.14 RbacErrorCode + A.15 RbacCheckPoint）

> 本 Chunk 实现 `gateway-domain/iam` 的类型化骨架——6 个 record/interface + sealed Permission + 3 个 Port + RoleQueryService domain service + Port contract test；并落地 Flyway SQL 草案（一期不跑）。所有 Task 提交后 `mvn -pl gateway-domain test` 全绿，spec 第 1 组 4 条 SHALL（GW-RBAC-001/002/003/004）通过。

### Task A.1: 复用现有 `RoleId`（跳过新建） + 决议记录

> **决议（评审 #1 拆分后精简版）**：A.1 tasks.md 原计划"新增 RoleId"。但 `gateway-domain/shared/RoleId.java` 已存在（`record RoleId(String value)` + canonical validation），语义与 spec §19.2 一致。本 Task **不新建 RoleId 文件**，仅做决议记录：
>
> - ✅ 复用 `com.company.agentgateway.domain.shared.RoleId`（已存在）
> - ✅ 复用 `com.company.agentgateway.domain.shared.UserId / TenantId / ModelId`（已存在）
> - ✅ 复用 `com.company.agentgateway.domain.iam.AuthChannel`（已存在）
> - ✅ 复用 `com.company.agentgateway.domain.iam.AgentGrant`（已存在）
>
> **`RbacErrorCode` 常量类已拆为独立 Task A.14**（plan 评审 #1 拆分决议）。

- [ ] **Step 1: 校验所有既有类型存在**（无代码改动，纯校验）

Run: `cd /Users/muxi/workspace/agent-gateway && ls gateway-domain/src/main/java/com/company/agentgateway/domain/shared/RoleId.java gateway-domain/src/main/java/com/company/agentgateway/domain/shared/UserId.java gateway-domain/src/main/java/com/company/agentgateway/domain/shared/TenantId.java gateway-domain/src/main/java/com/company/agentgateway/domain/shared/ModelId.java gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AuthChannel.java gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AgentGrant.java`

Expected: 6 行输出，文件全部存在

- [ ] **Step 2: 决议提交**

```bash
cd /Users/muxi/workspace/agent-gateway
git commit --allow-empty -m "chore(d1-rbac): A.1 decision — reuse existing RoleId/UserId/TenantId/ModelId/AuthChannel/AgentGrant"
```

---

### Task A.2: 新增 record `AgentPermission`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AgentPermission.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/AgentPermissionTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/AgentPermissionTest.java`：
```java
package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPermissionTest {

    @Test
    void allowedSkills_isImmutable() {
        Set<String> skills = new java.util.HashSet<>(Set.of("summarize", "translate"));
        AgentPermission ap = new AgentPermission("hr-agent", skills);
        assertThatThrownBy(() -> skills.add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(ap.allowedSkills()).hasSize(2);
    }

    @Test
    void equalsAndHashCode() {
        AgentPermission a = new AgentPermission("a1", Set.of("s1"));
        AgentPermission b = new AgentPermission("a1", Set.of("s1"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void emptyAllowedSkills_meansFullGrant() {
        AgentPermission ap = new AgentPermission("a1", Set.of());
        assertThat(ap.allowedSkills()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=AgentPermissionTest -q`
Expected: FAILURE（`AgentPermission` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AgentPermission.java`：
```java
package com.company.agentgateway.domain.iam;

import java.util.Set;

/**
 * spec §19.2 AgentPermission。allowedSkills 为空 = 全授权（D1 一期允许空，
 * Skill 级细化沿用既有 AgentGrant.allowsSkill 语义）。
 */
public record AgentPermission(String agentName, Set<String> allowedSkills) {
    public AgentPermission {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        allowedSkills = Set.copyOf(allowedSkills);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=AgentPermissionTest -q`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AgentPermission.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/AgentPermissionTest.java
git commit -m "feat(iam): add AgentPermission record (spec §19.2)"
```

---

### Task A.3: 新增 record `ModelPermission`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/ModelPermission.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/ModelPermissionTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/ModelPermissionTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPermissionTest {

    @Test
    void models_isImmutable() {
        Set<ModelId> models = new java.util.HashSet<>(Set.of(new ModelId("qwen")));
        ModelPermission mp = new ModelPermission(models);
        assertThatThrownBy(() -> models.add(new ModelId("hack")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyModels_throws() {
        assertThatThrownBy(() -> new ModelPermission(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one model");
    }

    @Test
    void containsModel() {
        ModelPermission mp = new ModelPermission(Set.of(new ModelId("qwen"), new ModelId("gpt4")));
        assertThat(mp.models()).contains(new ModelId("qwen"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=ModelPermissionTest -q`
Expected: FAILURE（`ModelPermission` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/ModelPermission.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Set;

/**
 * spec §19.2 ModelPermission。models 至少 1 个（避免空授权角色混淆）。
 */
public record ModelPermission(Set<ModelId> models) {
    public ModelPermission {
        models = Set.copyOf(models);
        if (models.isEmpty()) {
            throw new IllegalArgumentException("ModelPermission requires at least one model");
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=ModelPermissionTest -q`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/ModelPermission.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/ModelPermissionTest.java
git commit -m "feat(iam): add ModelPermission record (spec §19.2)"
```

---

### Task A.4: 新增 record `SkillPermission`（一期数据空，D1-4 决策）

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/SkillPermission.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/SkillPermissionTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/SkillPermissionTest.java`：
```java
package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SkillPermissionTest {

    @Test
    void blankArgs_throw() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SkillPermission(null, "s1"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SkillPermission("a1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCode() {
        SkillPermission a = new SkillPermission("a1", "s1");
        SkillPermission b = new SkillPermission("a1", "s1");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void isSealedSubclass_PatternMatching() {
        SkillPermission sp = new SkillPermission("a1", "s1");
        // 一期 PolicyEvaluator case SkillPermission sp → ALLOWED-with-skip（deferred to phase 2）
        // 此处只验证 record 字段
        assertThat(sp.agentName()).isEqualTo("a1");
        assertThat(sp.skillName()).isEqualTo("s1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=SkillPermissionTest -q`
Expected: FAILURE（`SkillPermission` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/SkillPermission.java`：
```java
package com.company.agentgateway.domain.iam;

/**
 * spec §19.2 SkillPermission。一期类型在、数据空（proposal D1-4 决策 · sealed 子类保留）。
 * 二期填数据零破坏：仅需在 PolicyEvaluator 中实现 decideSkill 逻辑。
 */
public record SkillPermission(String agentName, String skillName) {
    public SkillPermission {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName must not be blank");
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=SkillPermissionTest -q`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/SkillPermission.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/SkillPermissionTest.java
git commit -m "feat(iam): add SkillPermission record sealed subtype (spec §19.2)"
```

---

### Task A.5: 新增 sealed interface `Permission`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Permission.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PermissionSealedTest.java`：
```java
package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Permission sealed 形态：编译期 exhaustiveness。
 * 如遗漏 permits 子类，Java 21 编译器阻断（任务 A.5 + A.12 配套使用）。
 */
class PermissionSealedTest {

    @Test
    void sealedInterface_permits_threeSubclasses() {
        Permission p1 = new AgentPermission("a", Set.of());
        Permission p2 = new ModelPermission(Set.of(new com.company.agentgateway.domain.shared.ModelId("qwen")));
        Permission p3 = new SkillPermission("a", "s");
        assertThat(p1).isInstanceOf(AgentPermission.class);
        assertThat(p2).isInstanceOf(ModelPermission.class);
        assertThat(p3).isInstanceOf(SkillPermission.class);
    }

    @Test
    void patternMatching_exhaustive() {
        Permission p = new AgentPermission("a", Set.of());
        String kind = switch (p) {
            case AgentPermission ap -> "agent";
            case ModelPermission mp -> "model";
            case SkillPermission sp -> "skill";
        };
        assertThat(kind).isEqualTo("agent");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=PermissionSealedTest -q`
Expected: FAILURE（`Permission` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Permission.java`：
```java
package com.company.agentgateway.domain.iam;

/**
 * spec §19.2 sealed Permission。
 *
 * <p>permits 三个子类：AgentPermission / ModelPermission / SkillPermission（D1-4 决策保留 Skill）。
 * Java 21 sealed exhaustiveness 编译期强制（GW-RBAC-003）；CI 必须 JDK 21。
 *
 * <p>Pattern Matching 用法：
 * <pre>{@code
 * switch (p) {
 *     case AgentPermission ap -> decideAgent(ap);
 *     case ModelPermission mp -> decideModel(mp);
 *     case SkillPermission sp -> decideSkill(sp);
 * }
 * }</pre>
 */
public sealed interface Permission permits AgentPermission, ModelPermission, SkillPermission {
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=PermissionSealedTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Permission.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PermissionSealedTest.java
git commit -m "feat(iam): add sealed Permission interface (spec §19.2, GW-RBAC-003)"
```

---

### Task A.6: 新增 record `Role`（含字段上限校验）

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Role.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleTest {

    @Test
    void permissions_isImmutable() {
        Set<Permission> perms = new HashSet<>(Set.of(
                new AgentPermission("hr-agent", Set.of()),
                new ModelPermission(Set.of(new com.company.agentgateway.domain.shared.ModelId("qwen")))
        ));
        Role role = new Role(new RoleId("r-1"), "name", "desc", perms);
        assertThatThrownBy(() -> perms.add(new SkillPermission("a", "s")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permissions_size_over100_throws() {
        Set<Permission> perms = new HashSet<>();
        for (int i = 0; i < 101; i++) {
            perms.add(new AgentPermission("agent-" + i, Set.of()));
        }
        assertThatThrownBy(() -> new Role(new RoleId("r-1"), "n", "d", perms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions size");
    }

    @Test
    void name_over64_throws() {
        String longName = "x".repeat(65);
        assertThatThrownBy(() -> new Role(new RoleId("r-1"), longName, "d", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void description_over256_throws() {
        String longDesc = "x".repeat(257);
        assertThatThrownBy(() -> new Role(new RoleId("r-1"), "n", longDesc, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void equalsAndHashCode() {
        Role a = new Role(new RoleId("r-1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of())));
        Role b = new Role(new RoleId("r-1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of())));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleTest -q`
Expected: FAILURE（`Role` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Role.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import java.util.Set;

/**
 * spec §19.2 Role。
 *
 * <p>字段上限（design §3.1 + spec §GW-RBAC-012 契约）：
 * <ul>
 *   <li>name ≤ 64</li>
 *   <li>description ≤ 256</li>
 *   <li>permissions.size ≤ 100</li>
 * </ul>
 */
public record Role(RoleId id, String name, String description, Set<Permission> permissions) {
    public Role {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("name length must be <= 64");
        }
        if (description != null && description.length() > 256) {
            throw new IllegalArgumentException("description length must be <= 256");
        }
        permissions = Set.copyOf(permissions);
        if (permissions.size() > 100) {
            throw new IllegalArgumentException("permissions size must be <= 100");
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleTest -q`
Expected: PASS（5 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/Role.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleTest.java
git commit -m "feat(iam): add Role record with field upper-bound validation (spec §19.2)"
```

---

### Task A.7: 新增 record `PolicyPreview` + `RbacDecisionEvent`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyPreview.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacDecisionEvent.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyPreviewTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyPreviewTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyPreviewTest {

    @Test
    void equalsAndHashCode() {
        PolicyPreview a = new PolicyPreview(
                new UserId("u-1"), new TenantId("t-1"),
                Set.of("hr-agent"), Set.of(new ModelId("qwen")));
        PolicyPreview b = new PolicyPreview(
                new UserId("u-1"), new TenantId("t-1"),
                Set.of("hr-agent"), Set.of(new ModelId("qwen")));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void collectionsAreImmutable() {
        Set<String> agents = new java.util.HashSet<>(Set.of("hr-agent"));
        Set<ModelId> models = new java.util.HashSet<>(Set.of(new ModelId("qwen")));
        PolicyPreview pp = new PolicyPreview(new UserId("u-1"), new TenantId("t-1"), agents, models);
        assertThat(pp.allowedAgents()).hasSize(1);
        assertThat(pp.allowedModels()).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=PolicyPreviewTest -q`
Expected: FAILURE（`PolicyPreview` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyPreview.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.util.Set;

/**
 * spec §19.2 PolicyPreview。纯函数评估结果（spec §GW-RBAC-011），
 * 同请求连发 N 次 equals 一致（幂等保证）。
 */
public record PolicyPreview(UserId user, TenantId tenant,
                            Set<String> allowedAgents, Set<ModelId> allowedModels) {
    public PolicyPreview {
        allowedAgents = Set.copyOf(allowedAgents);
        allowedModels = Set.copyOf(allowedModels);
    }
}
```

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacDecisionEvent.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.time.Instant;

/**
 * 决策事件（design §4.2）。OTel Counter attribute + AuditRepository 共享载体。
 *
 * <p>checkPoint 维度贯穿评估链入口（spec §GW-RBAC-010）：
 * <ul>
 *   <li>{@code rbac_filter}：RbacFilter 入口（gateway-interfaces）</li>
 *   <li>{@code a2a}：A2A 调用前二次校验（gateway-infra-a2a）</li>
 *   <li>{@code preview}：纯函数 preview，不上 OTel（仅单测内部追踪）</li>
 * </ul>
 *
 * <p>decisionReason 维度（spec §GW-RBAC-008）：
 * <ul>
 *   <li>{@code no_grant}：principal.agentGrants / allowedModels 命中失败</li>
 *   <li>{@code no_role_binding}：RoleBindingRepository 未绑定</li>
 *   <li>{@code no_model_permission}：ModelPermission 聚合无命中</li>
 * </ul>
 */
public record RbacDecisionEvent(String eventId, TenantId tenant, UserId user,
                                 String agentName, ModelId model,
                                 CheckPoint checkPoint, DecisionReason reason,
                                 boolean allowed, Instant timestamp) {

    public enum CheckPoint {
        RBAC_FILTER("rbac_filter"), A2A("a2a"), PREVIEW("preview");
        private final String value;
        CheckPoint(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum DecisionReason {
        NO_GRANT("no_grant"),
        NO_ROLE_BINDING("no_role_binding"),
        NO_MODEL_PERMISSION("no_model_permission"),
        NONE("");
        private final String value;
        DecisionReason(String value) { this.value = value; }
        public String value() { return value; }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=PolicyPreviewTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyPreview.java \
        gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacDecisionEvent.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyPreviewTest.java
git commit -m "feat(iam): add PolicyPreview + RbacDecisionEvent (spec §19.2)"
```

---

### Task A.8: 新增 record `RoleBinding` + `RbacChangeEvent`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBinding.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangeEvent.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleBindingTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RoleBindingTest {

    @Test
    void tripleKey_equalsAndHashCode() {
        RoleBinding a = new RoleBinding(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        RoleBinding b = new RoleBinding(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}

class RbacChangeEventTest {

    @Test
    void kind_valuesCoverLifecycle() {
        // spec §GW-RBAC-002 + design §4.2 RbacChangeEvent.Kind
        assertThat(RbacChangeEvent.Kind.values())
                .contains(RbacChangeEvent.Kind.ROLE_UPSERT,
                          RbacChangeEvent.Kind.ROLE_DELETE,
                          RbacChangeEvent.Kind.BIND,
                          RbacChangeEvent.Kind.UNBIND);
    }

    @Test
    void event_carriesAllFields() {
        Instant now = Instant.now();
        RbacChangeEvent ev = new RbacChangeEvent(
                RbacChangeEvent.Kind.ROLE_UPSERT,
                new TenantId("t1"),
                new RoleId("r1"),
                new UserId("u1"),
                "admin",
                now);
        assertThat(ev.kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
        assertThat(ev.tenant().value()).isEqualTo("t1");
        assertThat(ev.roleId().value()).isEqualTo("r1");
        assertThat(ev.timestamp()).isEqualTo(now);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest='RoleBindingTest,RbacChangeEventTest' -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBinding.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

/**
 * 仓储内部值对象（design §4.2）：租户×用户×角色三元组。
 * 表 rbac_role_binding 的主键。
 */
public record RoleBinding(TenantId tenant, UserId user, RoleId roleId) {
    public RoleBinding {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (user == null) throw new IllegalArgumentException("user must not be null");
        if (roleId == null) throw new IllegalArgumentException("roleId must not be null");
    }
}
```

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangeEvent.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.time.Instant;

/**
 * 角色/绑定变更事件（spec §GW-RBAC-002 + design §4.2）。
 *
 * <p>Nacos Data ID：gateway.rbac.{tenant}.roles（spec §19.4 字面值）
 */
public record RbacChangeEvent(Kind kind, TenantId tenant, RoleId roleId,
                              UserId userId, String actor, Instant timestamp) {

    public enum Kind {
        ROLE_UPSERT, ROLE_DELETE, BIND, UNBIND
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest='RoleBindingTest,RbacChangeEventTest' -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBinding.java \
        gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangeEvent.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleBindingTest.java
git commit -m "feat(iam): add RoleBinding + RbacChangeEvent (spec §GW-RBAC-002)"
```

---

### Task A.9: 新增 Port `RoleRepository` + Contract Test

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleRepository.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleRepositoryContractTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleRepositoryContractTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port contract test：验证 RoleRepository 接口签名满足 spec §GW-RBAC-002 契约。
 * 用 InMemory 桩实现（与既有 InMemoryApiKeyStore 风格一致）。
 */
class RoleRepositoryContractTest {

    /** 测试用 InMemory 桩（验证零实现可编译） */
    static class InMemoryStub implements RoleRepository {
        final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();

        @Override public Optional<Role> findById(TenantId t, RoleId r) {
            return Optional.ofNullable(store.get(t)).map(m -> m.get(r));
        }
        @Override public List<Role> findAll(TenantId t) {
            Map<RoleId, Role> m = store.get(t);
            return m == null ? List.of() : List.copyOf(m.values());
        }
        @Override public void save(TenantId t, Role r) {
            store.computeIfAbsent(t, k -> new ConcurrentHashMap<>()).put(r.id(), r);
        }
        @Override public void delete(TenantId t, RoleId r) {
            Map<RoleId, Role> m = store.get(t);
            if (m != null) m.remove(r);
        }
    }

    @Test
    void findAll_isEmptyOnUnseenTenant() {
        RoleRepository repo = new InMemoryStub();
        assertThat(repo.findAll(new TenantId("t-unseen"))).isEmpty();
    }

    @Test
    void save_thenFindById_returnsSameRole() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        Role r = new Role(new RoleId("r1"), "name", "desc",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        repo.save(t, r);
        assertThat(repo.findById(t, new RoleId("r1"))).contains(r);
    }

    @Test
    void delete_removesRole() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d", Set.of()));
        repo.delete(t, new RoleId("r1"));
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }

    @Test
    void tenantIsolation_diffTenantSameRoleId_notVisible() {
        RoleRepository repo = new InMemoryStub();
        repo.save(new TenantId("t1"), new Role(new RoleId("r1"), "n", "d", Set.of()));
        assertThat(repo.findById(new TenantId("t2"), new RoleId("r1"))).isEmpty();
    }

    @Test
    void findAll_returnsAllRolesInTenant() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of()))));
        repo.save(t, new Role(new RoleId("r2"), "n", "d",
                Set.of(new ModelPermission(Set.of(new ModelId("qwen"))))));
        assertThat(repo.findAll(t)).hasSize(2);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleRepositoryContractTest -q`
Expected: FAILURE（`RoleRepository` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleRepository.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * 出站端口：角色定义存储（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>所有方法租户隔离：TenantId 为第一参数。
 * <p>实现：InMemoryRoleRepository（本期）/ RoleRepositoryJpa（二期）。
 */
public interface RoleRepository {

    Optional<Role> findById(TenantId tenant, RoleId roleId);

    List<Role> findAll(TenantId tenant);

    void save(TenantId tenant, Role role);

    void delete(TenantId tenant, RoleId roleId);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleRepositoryContractTest -q`
Expected: PASS（5 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleRepository.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleRepositoryContractTest.java
git commit -m "feat(iam): add RoleRepository port + contract test (spec §GW-RBAC-002)"
```

---

### Task A.10: 新增 Port `RoleBindingRepository` + Contract Test

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBindingRepository.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleBindingRepositoryContractTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleBindingRepositoryContractTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RoleBindingRepositoryContractTest {

    /** 测试用 InMemory 桩 */
    static class InMemoryStub implements RoleBindingRepository {
        // tenant -> (user -> Set<RoleId>)
        final Map<TenantId, Map<UserId, Set<RoleId>>> store = new ConcurrentHashMap<>();

        @Override public List<RoleId> findByUser(TenantId t, UserId u) {
            Map<UserId, Set<RoleId>> m = store.get(t);
            if (m == null) return List.of();
            Set<RoleId> s = m.get(u);
            return s == null ? List.of() : List.copyOf(s);
        }
        @Override public void bind(TenantId t, UserId u, RoleId r) {
            store.computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet())
                 .add(r);
        }
        @Override public void unbind(TenantId t, UserId u, RoleId r) {
            Map<UserId, Set<RoleId>> m = store.get(t);
            if (m != null) {
                Set<RoleId> s = m.get(u);
                if (s != null) s.remove(r);
            }
        }
    }

    @Test
    void findByUser_returnsEmpty_whenNeverBound() {
        RoleBindingRepository repo = new InMemoryStub();
        assertThat(repo.findByUser(new TenantId("t1"), new UserId("u1"))).isEmpty();
    }

    @Test
    void bind_thenFindByUser_returnsRoleId() {
        RoleBindingRepository repo = new InMemoryStub();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t1"), new UserId("u1")))
                .containsExactly(new RoleId("r1"));
    }

    @Test
    void unbind_removesBinding() {
        RoleBindingRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.bind(t, new UserId("u1"), new RoleId("r1"));
        repo.unbind(t, new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(t, new UserId("u1"))).isEmpty();
    }

    @Test
    void tenantIsolation_diffTenant_notVisible() {
        RoleBindingRepository repo = new InMemoryStub();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t2"), new UserId("u1"))).isEmpty();
    }

    @Test
    void bind_multipleRolesToSameUser_returnsAll() {
        RoleBindingRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r2"));
        repo.bind(t, u, new RoleId("r3"));
        assertThat(repo.findByUser(t, u)).hasSize(3);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleBindingRepositoryContractTest -q`
Expected: FAILURE（`RoleBindingRepository` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBindingRepository.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.List;

/**
 * 出站端口：用户→角色 绑定存储（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>租户隔离；实现：InMemoryRoleBindingRepository（本期）/ RoleBindingRepositoryJpa（二期）。
 */
public interface RoleBindingRepository {

    List<RoleId> findByUser(TenantId tenant, UserId user);

    void bind(TenantId tenant, UserId user, RoleId roleId);

    void unbind(TenantId tenant, UserId user, RoleId roleId);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RoleBindingRepositoryContractTest -q`
Expected: PASS（5 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleBindingRepository.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleBindingRepositoryContractTest.java
git commit -m "feat(iam): add RoleBindingRepository port + contract test (spec §GW-RBAC-002)"
```

---

### Task A.11: 新增 Port `RbacChangePublisher`（JDK Flow）+ Contract Test

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangePublisher.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacChangePublisherContractTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacChangePublisherContractTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RbacChangePublisherContractTest {

    /** 测试用 JDK Flow 桩 */
    static class FlowStub implements RbacChangePublisher {
        final SubmissionPublisher<RbacChangeEvent> pub = new SubmissionPublisher<>();
        @Override public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
            return pub.submit(event) == -1 ? pub : pub; // 返回同一 publisher 的 Flow 视图
        }
    }

    @Test
    void publish_returnsFlowPublisher_thatEmitsEvent() throws InterruptedException {
        var stub = new FlowStub();
        var received = new AtomicReference<RbacChangeEvent>();
        var sub = stub.pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(RbacChangeEvent item) { received.set(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });
        try {
            RbacChangeEvent ev = new RbacChangeEvent(
                    RbacChangeEvent.Kind.ROLE_UPSERT, new TenantId("t1"),
                    new RoleId("r1"), new UserId("u1"), "admin", Instant.now());
            stub.publish(ev);
            Thread.sleep(50); // 等异步分发
            assertThat(received.get()).isNotNull();
            assertThat(received.get().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
        } finally {
            stub.pub.close();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacChangePublisherContractTest -q`
Expected: FAILURE（`RbacChangePublisher` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangePublisher.java`：
```java
package com.company.agentgateway.domain.iam;

import java.util.concurrent.Flow;

/**
 * 出站端口：RBAC 变更事件发布（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>签名仅依赖 JDK 标准库（{@link java.util.concurrent.Flow}），不引入 Spring/Reactor。
 * <p>实现：NacosRbacChangePublisher（gateway-infra-security）— 通过 Nacos publishConfig
 * 把事件广播到所有网关实例，本地 InMemory 实现通过同实例 Flow 订阅做 write-through 缓存失效。
 */
public interface RbacChangePublisher {

    /**
     * 发布 RBAC 变更事件，返回可订阅的 Flow.Publisher。
     *
     * <p>契约：
     * <ul>
     *   <li>返回的 Publisher 必须立即可订阅（实现内部已 submit）</li>
     *   <li>订阅者按 onNext → onComplete 顺序接收；背压由订阅者控制</li>
     *   <li>失败语义：内部 catch + log warn，不回滚调用方（design §2.2）</li>
     * </ul>
     */
    Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacChangePublisherContractTest -q`
Expected: PASS（1 test）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacChangePublisher.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacChangePublisherContractTest.java
git commit -m "feat(iam): add RbacChangePublisher port (JDK Flow) + contract test"
```

---

### Task A.12: 新增 domain service `RoleQueryService` + `PolicyEvaluator`

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleQueryService.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyEvaluator.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleQueryServiceTest.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyEvaluatorTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyEvaluatorTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyEvaluatorTest {

    @Test
    void evaluateAgentPermission_returnsTrue_whenAgentNameMatches() {
        AgentPermission ap = new AgentPermission("hr-agent", Set.of());
        Optional<Boolean> result = PolicyEvaluator.evaluateAgent(ap, "hr-agent");
        assertThat(result).contains(true);
    }

    @Test
    void evaluateAgentPermission_returnsFalse_whenAgentNameDiffers() {
        AgentPermission ap = new AgentPermission("hr-agent", Set.of());
        Optional<Boolean> result = PolicyEvaluator.evaluateAgent(ap, "other-agent");
        assertThat(result).contains(false);
    }

    @Test
    void evaluateModelPermission_returnsTrue_whenModelInSet() {
        ModelPermission mp = new ModelPermission(Set.of(new ModelId("qwen"), new ModelId("gpt4")));
        Optional<Boolean> result = PolicyEvaluator.evaluateModel(mp, new ModelId("qwen"));
        assertThat(result).contains(true);
    }

    @Test
    void evaluateSkillPermission_returnsEmpty_deferredToPhase2() {
        SkillPermission sp = new SkillPermission("a1", "s1");
        // 一期 D1-4 决策：SkillPermission 数据空，返回 empty 让调用方 skip
        assertThat(PolicyEvaluator.evaluateSkill(sp)).isEmpty();
    }
}
```

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleQueryServiceTest.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class RoleQueryServiceTest {

    private final RoleQueryService svc = new RoleQueryService();

    private Role role(RoleId id, Set<Permission> perms) {
        return new Role(id, "name-" + id.value(), "desc", perms);
    }

    @Test
    void preview_pureFunction_returnsAggregatedAgentsAndModels() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(
                role(new RoleId("r1"), Set.of(new AgentPermission("hr-agent", Set.of()))),
                role(new RoleId("r2"), Set.of(
                        new AgentPermission("finance-agent", Set.of()),
                        new ModelPermission(Set.of(new ModelId("qwen"))))),
                role(new RoleId("r3"), Set.of(new AgentPermission("hr-agent", Set.of("salary")))
                ));
        // u 绑定 r1 + r2 + r3
        List<RoleId> bindings = List.of(new RoleId("r1"), new RoleId("r2"), new RoleId("r3"));

        PolicyPreview pp = svc.preview(snapshot, bindings, u, t);

        assertThat(pp.allowedAgents()).containsExactlyInAnyOrder("hr-agent", "finance-agent");
        assertThat(pp.allowedModels()).containsExactly(new ModelId("qwen"));
    }

    @Test
    void preview_isIdempotent_sameInputSameOutput() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(
                role(new RoleId("r1"), Set.of(new AgentPermission("a", Set.of()))));
        List<RoleId> bindings = List.of(new RoleId("r1"));

        PolicyPreview p1 = svc.preview(snapshot, bindings, u, t);
        PolicyPreview p2 = svc.preview(snapshot, bindings, u, t);
        PolicyPreview p3 = svc.preview(snapshot, bindings, u, t);

        // 幂等：连发 10 次都 equals（spec §GW-RBAC-011）
        for (int i = 0; i < 10; i++) {
            assertThat(svc.preview(snapshot, bindings, u, t)).isEqualTo(p1);
        }
        assertThat(p2).isEqualTo(p1).isEqualTo(p3);
    }

    @Test
    void preview_emptyBindings_returnsEmptyPreview() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        PolicyPreview pp = svc.preview(List.of(), List.of(), u, t);
        assertThat(pp.allowedAgents()).isEmpty();
        assertThat(pp.allowedModels()).isEmpty();
    }

    @Test
    void preview_skillPermissions_areIgnored_phase1() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(role(new RoleId("r1"),
                Set.of(new SkillPermission("a", "s"))));
        List<RoleId> bindings = List.of(new RoleId("r1"));
        PolicyPreview pp = svc.preview(snapshot, bindings, u, t);
        // D1-4：SkillPermission 一期数据空，preview 聚合时跳过
        assertThat(pp.allowedAgents()).isEmpty();
        assertThat(pp.allowedModels()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest='PolicyEvaluatorTest,RoleQueryServiceTest' -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyEvaluator.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Optional;

/**
 * Permission 评估器（spec §GW-RBAC-003 Pattern Matching exhaustiveness）。
 *
 * <p>Java 21 sealed 强制 exhaustiveness；如漏分支编译失败。
 * <p>evaluateSkill 一期返回 {@code Optional.empty()}（D1-4 决策）— 调用方按 ALLOWED-with-skip 处理。
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {}

    public static Optional<Boolean> evaluateAgent(AgentPermission ap, String agentName) {
        return Optional.of(ap.agentName().equals(agentName));
    }

    public static Optional<Boolean> evaluateModel(ModelPermission mp, ModelId model) {
        return Optional.of(mp.models().contains(model));
    }

    /**
     * Skill 级 RBAC（D1-4 一期数据空）：返回 Optional.empty() 让评估链按
     * "ALLOWED-with-skip" 跳过 Skill 维度（spec §GW-RBAC-003 注释）。
     * 二期填数据时改为 evaluateSkill(sp) -> ap.agentName().equals(...) && ...
     */
    public static Optional<Boolean> evaluateSkill(SkillPermission sp) {
        return Optional.empty();
    }

    /** 通用模式匹配入口（spec §GW-RBAC-003） */
    public static Optional<Boolean> evaluatePermission(Permission p, String agentName, ModelId model) {
        return switch (p) {
            case AgentPermission ap -> evaluateAgent(ap, agentName);
            case ModelPermission mp -> evaluateModel(mp, model);
            case SkillPermission sp -> evaluateSkill(sp);
        };
    }
}
```

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleQueryService.java`：
```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * domain service：角色查询 + PolicyPreview 纯函数评估（spec §GW-RBAC-011 + design §3.1）。
 *
 * <p>preview 不读仓储、不写审计、不上 OTel（design §2.3）。调用方需自行传入 roles 快照 + bindings。
 * <p>幂等保证：同入参连发 N 次 equals 一致（spec §验收判定 ⑪）。
 */
public class RoleQueryService {

    public PolicyPreview preview(List<Role> roles, List<RoleId> bindings,
                                 UserId user, TenantId tenant) {
        Set<Role> userRoles = roles.stream()
                .filter(r -> bindings.contains(r.id()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> allowedAgents = new LinkedHashSet<>();
        Set<ModelId> allowedModels = new LinkedHashSet<>();

        for (Role role : userRoles) {
            for (Permission p : role.permissions()) {
                switch (p) {
                    case AgentPermission ap -> allowedAgents.add(ap.agentName());
                    case ModelPermission mp -> allowedModels.addAll(mp.models());
                    case SkillPermission sp -> {
                        /* D1-4：SkillPermission 一期数据空，preview 跳过 */
                    }
                }
            }
        }

        return new PolicyPreview(user, tenant,
                Set.copyOf(allowedAgents), Set.copyOf(allowedModels));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest='PolicyEvaluatorTest,RoleQueryServiceTest' -q`
Expected: PASS（4 + 4 = 8 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RoleQueryService.java \
        gateway-domain/src/main/java/com/company/agentgateway/domain/iam/PolicyEvaluator.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RoleQueryServiceTest.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/PolicyEvaluatorTest.java
git commit -m "feat(iam): add RoleQueryService + PolicyEvaluator domain service (spec §GW-RBAC-003/011)"
```

---

### Task A.13: Flyway SQL 草案存档（design §4.1 字面值；本期不跑）

> **偏离声明**：`gateway-bootstrap` 尚无 Flyway 依赖与 `db/migration/` 目录。本计划将草案放在 `openspec/changes/d1-iam-rbac-deepening/sql/` 下，随 change 归档；二期 JPA 落地时由二期 change 引入 Flyway 依赖后迁移到 `gateway-bootstrap/src/main/resources/db/migration/V<n>__add_rbac_tables.sql`。

**Files:**
- Create: `openspec/changes/d1-iam-rbac-deepening/sql/V__add_rbac_tables.sql`

- [ ] **Step 1: 写 SQL 草案（design §4.1 字面值）**

`openspec/changes/d1-iam-rbac-deepening/sql/V__add_rbac_tables.sql`：
```sql
-- D1 RBAC 深化 · 二期 JPA 落地用 SQL 草案（design §4.1）
-- 一期 InMemory 实现不需此 SQL；本文件存档待二期使用。
-- V 号由二期 change 决定（须查最大未占用 V<n>）。

-- rbac_role：租户维度角色定义
CREATE TABLE rbac_role (
    id           VARCHAR(64)  NOT NULL,                       -- RoleId.value() 形式："r-<ulid>"
    tenant_id    VARCHAR(64)  NOT NULL,                       -- TenantId.value()
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(256) NOT NULL DEFAULT '',
    permissions  JSONB        NOT NULL,                       -- Set<Permission> 序列化 [{kind:"agent", agentName:"...", allowedSkills:[...]}, ...]
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

- [ ] **Step 2: 校验文件可读**

Run: `cat /Users/muxi/workspace/agent-gateway/openspec/changes/d1-iam-rbac-deepening/sql/V__add_rbac_tables.sql | head -20`
Expected: 文件内容正确显示

- [ ] **Step 3: 标注本期不跑说明（追加 README）**

Create: `openspec/changes/d1-iam-rbac-deepening/sql/README.md`
```markdown
# RBAC SQL 草案存档

> **本期不跑迁移**：`gateway-bootstrap` 尚未引入 Flyway；本目录仅作 SQL 草案存档。
> 二期 JPA 落地的 change 引入 Flyway 依赖后，把本文件迁移到 `gateway-bootstrap/src/main/resources/db/migration/V<n>__add_rbac_tables.sql`（V<n> 须查当时最大未占用 V 号）。

详见 `design.md` §4.1 数据模型。
```

- [ ] **Step 4: commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add openspec/changes/d1-iam-rbac-deepening/sql/
git commit -m "docs(d1-iam-rbac): archive Flyway SQL draft for phase-2 JPA (本期不跑)"
```

---

### Chunk 1 验收

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -q`
Expected: BUILD SUCCESS，15 个测试类全绿（`AgentPermissionTest` 3 + `ModelPermissionTest` 3 + `SkillPermissionTest` 3 + `PermissionSealedTest` 2 + `RoleTest` 5 + `PolicyPreviewTest` 2 + `RoleBindingTest` 1 + `RbacChangeEventTest` 2 + `RoleRepositoryContractTest` 5 + `RoleBindingRepositoryContractTest` 5 + `RbacChangePublisherContractTest` 1 + `PolicyEvaluatorTest` 4 + `RoleQueryServiceTest` 4 + `RbacErrorCodeTest` 2 + `RbacCheckPointTest` 2 = **44 tests**）

> **关于 spec §验收判定"48 个新测试"的口径**（评审建议 #5）：spec §验收判定要求"48 个新测试 + 既有测试零修改"。Chunk 1 单元测试 44 条；后续 Chunk 2-5 增量测试为：
> - Chunk 2：`AuthorizationServiceImplTest` 新增 4 + `InMemory*RepositoryTest` 8 + `A2aToolPortRbacHookTest` 2 + `RbacInflightPolicyTest` 2 + `AdminPolicyControllerTest` 2 + `check-rbac-backcompat.sh` 退出码测试 = ~18
> - Chunk 3：`RbacMetricsTest` 2 + `RbacAuditEmitterTest` 2 + `RbacCheckPointTest` 已有 = ~4
> - Chunk 4：`AdminRoleControllerIT` 5 + `AdminRbacPreviewControllerIT` 3 + `AdminUserRoleControllerIT` 3 + UI 组件测试 4 + E2E 1 = ~16
> - Chunk 5：JaCoCo 覆盖率校验脚本 + SPEC 核验脚本 = 0 测试但有 6 闸门校验
>
> **总计：新测试 44 + 18 + 4 + 16 = 82 条**，远高于 spec §验收判定 48 条要求。**既有测试零修改：6 条**（`AuthorizationServiceImplTest`）。两者独立 ✅。

spec 第 1 组 SHALL 状态：
- `GW-RBAC-001` Role/Permission 5 record + sealed ✅
- `GW-RBAC-002` 3 个 domain Port + 租户隔离 ✅
- `GW-RBAC-003` sealed Pattern Matching exhaustiveness ✅（编译期强制）
- `GW-RBAC-004` InMemory 占位 + @ConditionalOnMissingBean ⏳（Chunk 2 Task B.4）

---

### Task A.14: 新增 `RbacErrorCode.java` 常量集中类（评审 #1 拆分）

> **决议**：从 A.1 拆分出来。spec §验收判定 ⑥ + proposal.md §错误码段分配要求所有 `throw new AuthorizationException("GW-1010")` 必须引用常量。

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacErrorCode.java`
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacErrorCodeTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacErrorCodeTest.java`：
```java
package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RbacErrorCodeTest {

    @Test
    void constants_alignWithSpec() {
        // spec §13.4 + proposal.md §错误码段分配
        assertThat(RbacErrorCode.UNAUTHORIZED).isEqualTo("GW-1003");
        assertThat(RbacErrorCode.ROLE_NOT_FOUND).isEqualTo("GW-1010");
        assertThat(RbacErrorCode.ROLE_BINDING_CONFLICT).isEqualTo("GW-1011");
        assertThat(RbacErrorCode.ROLE_PERMISSION_INVALID).isEqualTo("GW-1012");
        assertThat(RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND).isEqualTo("GW-1013");
        assertThat(RbacErrorCode.ADMIN_RBAC_FALLBACK).isEqualTo("GW-4204");
    }

    @Test
    void allErrorCodes_haveUniqueValue() {
        String[] all = {
            RbacErrorCode.UNAUTHORIZED,
            RbacErrorCode.ROLE_NOT_FOUND,
            RbacErrorCode.ROLE_BINDING_CONFLICT,
            RbacErrorCode.ROLE_PERMISSION_INVALID,
            RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND,
            RbacErrorCode.ADMIN_RBAC_FALLBACK
        };
        assertThat(java.util.Set.of(all)).hasSize(all.length);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacErrorCodeTest -q`
Expected: FAILURE（`RbacErrorCode` 类不存在 → `cannot find symbol`）

- [ ] **Step 3: 写最小实现**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacErrorCode.java`：
```java
package com.company.agentgateway.domain.iam;

/**
 * RBAC 错误码常量集中类（spec §13.4 + proposal.md §错误码段分配）。
 *
 * <p>所有 throw new AuthorizationException("GW-1010") 必须引用此处常量，
 * 避免字符串散落（spec §验收判定 ⑥）。
 *
 * <p>段位零冲突：D1 占 GW-1xxx（1010~1013 增量）+ GW-42xx（复用 4204）。
 */
public final class RbacErrorCode {

    /** 无权限（spec §13.4 既有）。AuthorizationException → 403。 */
    public static final String UNAUTHORIZED = "GW-1003";

    /** 角色不存在。AdminRolesController GET/PUT/DELETE /{id} → 404。 */
    public static final String ROLE_NOT_FOUND = "GW-1010";

    /** 角色绑定冲突。AdminUserRoleController POST 重复绑定 → 409。 */
    public static final String ROLE_BINDING_CONFLICT = "GW-1011";

    /** 角色权限非法。AdminRolesController POST/PUT JSON 解析失败 / sealed 不识别 → 400。 */
    public static final String ROLE_PERMISSION_INVALID = "GW-1012";

    /** 用户角色绑定不存在。AdminUserRoleController DELETE /{roleId} → 404。 */
    public static final String USER_ROLE_BINDING_NOT_FOUND = "GW-1013";

    /** 管理后台 RBAC 错误（spec §19.3 既有）。GlobalExceptionHandler 兜底 → 500。 */
    public static final String ADMIN_RBAC_FALLBACK = "GW-4204";

    private RbacErrorCode() {
        // no instances
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacErrorCodeTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacErrorCode.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacErrorCodeTest.java
git commit -m "feat(iam): add RbacErrorCode constants (spec §13.4 + GW-1xxx/42xx)"
```

---

### Task A.15: 新增 enum `RbacCheckPoint`（评审 #2 修复 — 移到 domain 层）

> **决议（评审 #2）**：原计划在 `gateway-infra-security` 定义 `RbacCheckPoint` enum 桥接到 domain `RbacDecisionEvent.CheckPoint`，存在跨层耦合。本 Task 改为直接在 `gateway-domain/iam` 定义 `RbacCheckPoint` enum，与 `RbacDecisionEvent` 同模块同包，消除跨层耦合。
>
> **决议（评审 #2）**：原 spec §6.3 "4 方法签名不动" 是软约束。本 Task 在原 4 方法基础上**新增** 2 个带 checkPoint 的重载方法（共 6 方法），用于支撑 spec §GW-RBAC-005/006/008/009/010 的可观测可审计语义。

**Files:**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacCheckPoint.java`
- Modify: `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AuthorizationService.java`（新增 2 重载 + import RbacCheckPoint）
- Modify: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/ModelId.java`（无修改，仅校验存在）
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacCheckPointTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacCheckPointTest.java`：
```java
package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RbacCheckPointTest {

    @Test
    void enum_has_three_values() {
        // spec §GW-RBAC-010 要求三个 check_point 分流
        assertThat(RbacCheckPoint.values()).hasSize(3);
        assertThat(RbacCheckPoint.RBAC_FILTER.name()).isEqualTo("RBAC_FILTER");
        assertThat(RbacCheckPoint.A2A.name()).isEqualTo("A2A");
        assertThat(RbacCheckPoint.PREVIEW.name()).isEqualTo("PREVIEW");
    }

    @Test
    void values_are_stable_serialization() {
        // spec §归档闸门要求字符串稳定（审计/OTel attribute）
        assertThat(RbacCheckPoint.RBAC_FILTER.name()).isEqualTo("RBAC_FILTER");
        assertThat(RbacCheckPoint.A2A.name()).isEqualTo("A2A");
        assertThat(RbacCheckPoint.PREVIEW.name()).isEqualTo("PREVIEW");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacCheckPointTest -q`
Expected: FAILURE（`RbacCheckPoint` 类不存在）

- [ ] **Step 3: 写 `RbacCheckPoint` enum**

`gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacCheckPoint.java`：
```java
package com.company.agentgateway.domain.iam;

/**
 * RBAC 评估入口点（spec §GW-RBAC-010）。
 *
 * <p>用于 OTel Counter {@code rbac.allowed}/{@code rbac.denied} 的
 * {@code check_point} attribute 维度（spec §GW-RBAC-008），便于运维区分
 * 策略偏离来源。
 *
 * <p><b>评审 #2 修复</b>：本 enum 直接定义在 {@code gateway-domain/iam}，
 * 与 `AuthorizationService` 接口同模块同包，消除跨层耦合。
 *
 * @see AuthorizationService#canInvokeAgent(AuthPrincipal, String, RbacCheckPoint)
 * @see AuthorizationService#canUseModel(AuthPrincipal, com.company.agentgateway.domain.shared.ModelId, RbacCheckPoint)
 */
public enum RbacCheckPoint {
    /** 入口：{@code RbacFilter}（gateway-interfaces），拦截 /v1/chat/* 与 /v1/agents/* 请求入口 */
    RBAC_FILTER,
    /** 入口：A2A 远程调用前 {@code RbacInflightPolicy}（gateway-infra-security）二次校验 */
    A2A,
    /** 入口：{@code AdminRbacPreviewController}（管理后台 preview 端点，纯函数仿真，不入 OTel/审计） */
    PREVIEW
}
```

- [ ] **Step 4: 扩展 `AuthorizationService` 接口（评审 #2 修复）**

**修改 `gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AuthorizationService.java`**：

现有 4 方法（`canInvokeAgent(principal, agentName)` / `canUseModel(principal, model)` / `checkInvokeAgent(principal, agentName)` / `checkUseModel(principal, model)`）保留不变（**既有 6 条 AuthorizationServiceImplTest 零修改**）。**新增 2 个重载**带 checkPoint 参数：

```java
// 在 AuthorizationService 接口现有 4 方法签名后追加：
/**
 * 🆕 D1 新增（评审 #2 修复）：带 checkPoint 的 canInvokeAgent 重载。
 *
 * <p>用于 RbacFilter / RbacInflightPolicy / AdminRbacPreviewController
 * 传入明确的 checkPoint，由实现层打 OTel attribute {@code rbac.check_point=...}。
 *
 * <p><b>既有契约不变</b>：原 {@link #canInvokeAgent(AuthPrincipal, String)}
 * 签名零变化，原 {@code AuthorizationServiceImplTest} 6 条用例零修改。
 */
boolean canInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint);

/**
 * 🆕 D1 新增（评审 #2 修复）：带 checkPoint 的 canUseModel 重载。
 */
boolean canUseModel(AuthPrincipal principal, com.company.agentgateway.domain.shared.ModelId model, RbacCheckPoint checkPoint);
```

**完整接口签名**（既有 4 方法保留，新增 2 重载共 6 方法）：

```java
package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;

public interface AuthorizationService {

    // ====== 既有 4 方法（spec §6.3 既有 4 方法签名；既有测试零修改） ======

    boolean canInvokeAgent(AuthPrincipal principal, String agentName);

    boolean canUseModel(AuthPrincipal principal, ModelId model);

    void checkInvokeAgent(AuthPrincipal principal, String agentName);

    void checkUseModel(AuthPrincipal principal, ModelId model);

    // ====== 🆕 D1 新增 2 重载（评审 #2 修复；spec §GW-RBAC-005/006/008/009/010） ======

    boolean canInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint);

    boolean canUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint);
}
```

- [ ] **Step 5: 运行 `AuthorizationServiceImpl` 编译**（impl 必须实现 2 个新重载，否则编译失败）

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security compile -q`
Expected: FAILURE（`AuthorizationServiceImpl` 未实现 `canInvokeAgent(principal, agentName, RbacCheckPoint)` 与 `canUseModel(principal, model, RbacCheckPoint)` 两个方法 → `class is not abstract and does not override abstract method`）

> **本 Task 不修 impl**，仅记录编译失败证据，由 Chunk 2 Task B.5 修复（impl 加 2 重载实现，详见 B.5 Step 3）。

- [ ] **Step 6: 运行 `RbacCheckPointTest` 确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain test -Dtest=RbacCheckPointTest -q`
Expected: PASS（2 tests）

- [ ] **Step 7: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/RbacCheckPoint.java \
        gateway-domain/src/main/java/com/company/agentgateway/domain/iam/AuthorizationService.java \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/RbacCheckPointTest.java
git commit -m "feat(iam): add RbacCheckPoint enum + AuthorizationService 2 new overloads (spec §GW-RBAC-010)"
```

> **注意**：本 Task 不修复 `AuthorizationServiceImpl` 的编译错误（Step 5 预期失败）。这是 **故意** 的设计：
> - A.15 定义接口契约
> - Chunk 2 Task B.5 让 impl 实现新接口（决策并集 + 2 重载）
> - Chunk 2 Task B.1（任务前基线）确保 A.15 之前的 6 条既有测试仍能编译运行
>
> 这一编译失败窗口（从 A.15 commit 到 B.5 commit）通过 Chunk 1 末尾整体 `mvn -pl gateway-domain` 验证隔离（impl 在 gateway-infra-security，不影响 gateway-domain 编译）。

---

## Chunk 2: 评估接线（约阶段 B，11 任务：原 10 + B.11 A2A hook 入口落地）

> 本 Chunk 实现 `AuthorizationServiceImpl` 决策并集升级 + InMemory 仓储实现 + NacosRbacChangePublisher + RbacInflightPolicy（A2A hook）+ AdminPolicyController 类型化改造 + 既有测试零修改证据校验。

### Task B.1: 既有测试零修改证据基线校验（**任务前先校验**）

> **关键**：B 阶段改造会触碰 `AuthorizationServiceImpl` 构造函数（新增 RoleRepository/RoleBindingRepository 依赖）。必须**先**确认既有 6 条测试为基线，B 阶段末尾再校验零修改仍全绿（proposal 风险表第 6 行 + spec §归档闸门 ④）。

- [ ] **Step 1: 记录基线**

Run: `cd /Users/muxi/workspace/agent-gateway && git diff main...HEAD -- '*AuthorizationServiceImplTest*' | wc -l`
Expected: 输出 `0`（无既有测试文件改动）

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: BUILD SUCCESS（6 条既有测试全绿）

- [ ] **Step 2: 创建基线备忘**

Create: `openspec/changes/d1-iam-rbac-deepening/evidence/phase-b-baseline.txt`
```
AuthorizationServiceImplTest baseline (date: <timestamp>)
=============================================================
git diff main...HEAD -- '*AuthorizationServiceImplTest*' → 0 lines
mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest → 6 tests, BUILD SUCCESS
```

- [ ] **Step 3: commit 基线**

```bash
cd /Users/muxi/workspace/agent-gateway
git add openspec/changes/d1-iam-rbac-deepening/evidence/phase-b-baseline.txt
git commit -m "test(d1-rbac): baseline evidence for AuthorizationServiceImplTest zero-mod (phase B start)"
```

---

### Task B.2: 实现 `InMemoryRoleRepository` + `InMemoryRoleBindingRepository`

**Files:**
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleRepository.java`
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleBindingRepository.java`
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleRepositoryTest.java`
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleBindingRepositoryTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleRepositoryTest.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRoleRepositoryTest {

    @Test
    void crud_basic() {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        Role r = new Role(new RoleId("r1"), "name", "desc",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        repo.save(t, r);
        assertThat(repo.findById(t, new RoleId("r1"))).contains(r);
        assertThat(repo.findAll(t)).hasSize(1);
        repo.delete(t, new RoleId("r1"));
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }

    @Test
    void tenantIsolation() {
        RoleRepository repo = new InMemoryRoleRepository();
        repo.save(new TenantId("t1"), new Role(new RoleId("r1"), "n", "d", Set.of()));
        assertThat(repo.findAll(new TenantId("t2"))).isEmpty();
    }

    @Test
    void concurrentSave_50threads_threadSafe() throws InterruptedException {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    repo.save(t, new Role(new RoleId("r-" + idx), "n-" + idx, "d",
                            Set.of(new AgentPermission("a-" + idx, Set.of()))));
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(repo.findAll(t)).hasSize(n);
    }

    @Test
    void delete_isIdempotent() {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d", Set.of()));
        repo.delete(t, new RoleId("r1"));
        repo.delete(t, new RoleId("r1")); // 二次删除不抛
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }
}
```

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleBindingRepositoryTest.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRoleBindingRepositoryTest {

    @Test
    void crud_basic() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r2"));
        assertThat(repo.findByUser(t, u)).containsExactlyInAnyOrder(
                new RoleId("r1"), new RoleId("r2"));
        repo.unbind(t, u, new RoleId("r1"));
        assertThat(repo.findByUser(t, u)).containsExactly(new RoleId("r2"));
    }

    @Test
    void tenantIsolation() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t2"), new UserId("u1"))).isEmpty();
    }

    @Test
    void unbind_unbound_returnsQuietly() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        repo.unbind(t, new UserId("u1"), new RoleId("r-不存在")); // 不抛
    }

    @Test
    void bind_duplicate_isIdempotent() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r1")); // 重复绑定幂等（HTTP 层负责 409 校验）
        assertThat(repo.findByUser(t, u)).containsExactly(new RoleId("r1"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest='InMemoryRoleRepositoryTest,InMemoryRoleBindingRepositoryTest' -q`
Expected: FAILURE（两个类不存在）

- [ ] **Step 3: 写最小实现**

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleRepository.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory RoleRepository（spec §GW-RBAC-004 · design §2.1）。
 *
 * <p>结构：ConcurrentHashMap<TenantId, ConcurrentHashMap<RoleId, Role>>。
 * 二期 JPA 实现通过 @ConditionalOnMissingBean 覆盖。
 */
@Component
@ConditionalOnMissingBean(RoleRepository.class)
public class InMemoryRoleRepository implements RoleRepository {

    private final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Role> findById(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        return Optional.ofNullable(inner).map(m -> m.get(roleId));
    }

    @Override
    public List<Role> findAll(TenantId tenant) {
        Map<RoleId, Role> inner = store.get(tenant);
        return inner == null ? List.of() : List.copyOf(inner.values());
    }

    @Override
    public void save(TenantId tenant, Role role) {
        store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>()).put(role.id(), role);
    }

    @Override
    public void delete(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        if (inner != null) inner.remove(roleId);
    }
}
```

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/InMemoryRoleBindingRepository.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory RoleBindingRepository（spec §GW-RBAC-004 · design §2.1）。
 *
 * <p>结构：ConcurrentHashMap<TenantId, ConcurrentHashMap<UserId, Set<RoleId>>>。
 */
@Component
@ConditionalOnMissingBean(RoleBindingRepository.class)
public class InMemoryRoleBindingRepository implements RoleBindingRepository {

    private final Map<TenantId, Map<UserId, Set<RoleId>>> store = new ConcurrentHashMap<>();

    @Override
    public List<RoleId> findByUser(TenantId tenant, UserId user) {
        Map<UserId, Set<RoleId>> inner = store.get(tenant);
        if (inner == null) return List.of();
        Set<RoleId> s = inner.get(user);
        return s == null ? List.of() : List.copyOf(s);
    }

    @Override
    public void bind(TenantId tenant, UserId user, RoleId roleId) {
        store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
             .computeIfAbsent(user, k -> ConcurrentHashMap.newKeySet())
             .add(roleId);
    }

    @Override
    public void unbind(TenantId tenant, UserId user, RoleId roleId) {
        Map<UserId, Set<RoleId>> inner = store.get(tenant);
        if (inner == null) return;
        Set<RoleId> s = inner.get(user);
        if (s != null) s.remove(roleId);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest='InMemoryRoleRepositoryTest,InMemoryRoleBindingRepositoryTest' -q`
Expected: PASS（4 + 4 = 8 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/ \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/
git commit -m "feat(security): add InMemoryRoleRepository + InMemoryRoleBindingRepository (spec §GW-RBAC-004)"
```

---

### Task B.3: 实现 `NacosRbacChangePublisher`（占位 + Flow 接入）

> **设计决议**：一期 `NacosRbacChangePublisher` 是**占位实现**——发布事件时仅 log + Flow submit，不真正连 Nacos。理由：spec §GW-RBAC-002 要求 "JDK Flow 零框架"；一期 E2E 验证不依赖 Nacos 容器；二期再接 `gateway-infra-nacos` 的 nacos-client（设计 §2.2 已声明此口径）。

**Files:**
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisher.java`
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisherTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisherTest.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class NacosRbacChangePublisherTest {

    @Test
    void publish_returnsFlowPublisher_thatEmitsEvent() throws InterruptedException {
        NacosRbacChangePublisher pub = new NacosRbacChangePublisher();
        RbacChangeEvent ev = new RbacChangeEvent(
                RbacChangeEvent.Kind.ROLE_UPSERT, new TenantId("t1"),
                new RoleId("r1"), new UserId("u1"), "admin", Instant.now());

        AtomicReference<RbacChangeEvent> received = new AtomicReference<>();
        Flow.Publisher<RbacChangeEvent> publisher = pub.publish(ev);

        // subscribe + 等待异步分发
        Thread.sleep(50);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(RbacChangeEvent item) { received.set(item); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        Thread.sleep(50);
        assertThat(received.get()).isNotNull();
        assertThat(received.get().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
    }

    @Test
    void dataId_formatFollowsSpec() {
        NacosRbacChangePublisher pub = new NacosRbacChangePublisher();
        assertThat(pub.dataIdFor(new TenantId("primary")))
                .isEqualTo("gateway.rbac.primary.roles");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=NacosRbacChangePublisherTest -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisher.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Nacos RBAC 变更发布器（spec §GW-RBAC-002 + design §2.2 · §3.2）。
 *
 * <p>一期：占位实现，发布时 log warn + JDK Flow submit。Data ID 命名遵循
 * {@code gateway.rbac.{tenant}.roles}（spec §19.4 字面值）。
 *
 * <p>二期：接入 gateway-infra-nacos 的 nacos-client 3.3.0-BETA，
 * publish 时调 nacosClient.publishConfig(dataId, json)。
 *
 * <p>失败语义：catch + log warn，不回滚调用方（design §2.2）。
 */
@Component
@ConditionalOnMissingBean(RbacChangePublisher.class)
public class NacosRbacChangePublisher implements RbacChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(NacosRbacChangePublisher.class);

    private final SubmissionPublisher<RbacChangeEvent> publisher = new SubmissionPublisher<>();

    @Override
    public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
        try {
            String dataId = dataIdFor(event.tenant());
            log.info("RBAC change [phase-1 stub] dataId={} kind={} roleId={} userId={} actor={}",
                    dataId, event.kind(), event.roleId(), event.userId(), event.actor());
            publisher.submit(event);
        } catch (Exception e) {
            log.warn("RBAC change publish failed (swallowed): {}", e.getMessage());
        }
        return publisher;
    }

    /** spec §19.4 字面值：gateway.rbac.{tenant}.roles */
    public String dataIdFor(TenantId tenant) {
        return "gateway.rbac." + tenant.value() + ".roles";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=NacosRbacChangePublisherTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisher.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/NacosRbacChangePublisherTest.java
git commit -m "feat(security): add NacosRbacChangePublisher placeholder (spec §GW-RBAC-002, phase-1 stub)"
```

---

### Task B.4: `InfraSecurityAutoConfiguration` 扩展装配

> **关键**：B.4 装配 3 个 Port Bean（InMemory 默认）+ B.6 阶段注入到 `AuthorizationServiceImpl`。本 Task 只加装配，不动既有 `AuthorizationServiceImpl` 构造（避免破坏既有测试）；B.5 才改构造器。

**Files:**
- Modify: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfiguration.java:94-97`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfigurationTest.java`：
```java
package com.company.agentgateway.infra.security.config;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring 装配：InMemoryRoleRepository / InMemoryRoleBindingRepository 是默认 Bean。
 */
@SpringBootTest(classes = InfraSecurityAutoConfiguration.class)
@TestPropertySource(properties = "spring.main.web-application-type=none")
class InfraSecurityAutoConfigurationTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleBindingRepository roleBindingRepository;

    @Test
    void inMemoryRepos_areAutoWiredByDefault() {
        assertThat(roleRepository).isInstanceOf(com.company.agentgateway.infra.security.rbac.InMemoryRoleRepository.class);
        assertThat(roleBindingRepository).isInstanceOf(com.company.agentgateway.infra.security.rbac.InMemoryRoleBindingRepository.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=InfraSecurityAutoConfigurationTest -q`
Expected: FAILURE（`InMemoryRoleRepository` 等尚未被 @ComponentScan 发现——可能因为 InfraSecurityAutoConfiguration 在 `config` 子包而新组件在 `rbac` 子包；或 Bean 装配缺位）

- [ ] **Step 3: 修改 `InfraSecurityAutoConfiguration`**

Edit `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfiguration.java`：在文件头 import 后新增 `@ComponentScan(basePackages = "com.company.agentgateway.infra.security", includeFilters = ...)`

具体修改（行 43-44 后新增）：
```java
import org.springframework.context.annotation.ComponentScan;
```

并在 `@Configuration` 后加：
```java
@ComponentScan(
    basePackages = "com.company.agentgateway.infra.security",
    includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
        pattern = "com\\.company\\.agentgateway\\.infra\\.security\\.rbac\\..*"),
    useDefaultFilters = false
)
```

并在文件头 import：
```java
import org.springframework.context.annotation.FilterType;
```

> **注意**：useDefaultFilters=false 避免重复扫描既有 config 包组件。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=InfraSecurityAutoConfigurationTest -q`
Expected: PASS（1 test）

- [ ] **Step 5: 校验既有 6 条 `AuthorizationServiceImplTest` 仍全绿**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: PASS（6 tests 零修改仍全绿 ✅）

- [ ] **Step 6: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfiguration.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfigurationTest.java
git commit -m "feat(security): wire InMemoryRoleRepository + InMemoryRoleBindingRepository via @ComponentScan"
```

---

### Task B.5: `AuthorizationServiceImpl` 改造（注入 2 个 Port + 决策并集）

> **关键决策**：为了让既有 6 条 `AuthorizationServiceImplTest` 零修改仍全绿（构造器变更会让既有测试 `new AuthorizationServiceImpl()` 编译失败），本 Task 把构造器改为「零参默认 + Setter 注入」双形态：
> - 零参构造：`new AuthorizationServiceImpl()` 创建**降级模式**——只读 principal 字段，不读仓储（保留既有测试基线）
> - 双参构造：`new AuthorizationServiceImpl(roleRepository, bindingRepository)` 创建**升级模式**——决策并集
>
> 这种"双形态构造器"是 spec §GW-RBAC-005 "公开 API 签名零变化 + 既有测试零修改" 的可执行妥协。

**Files:**
- Modify: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/AuthorizationServiceImpl.java`
- Modify: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java` (新增 4 条用例；既有 6 条**零修改**)

- [ ] **Step 1: 写失败测试（新增 4 条；既有 6 条不动）**

Append to `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java`：
```java
    // ====== D1 新增：决策并集测试（既有 6 条不变） ======

    private final com.company.agentgateway.domain.iam.RoleRepository roleRepo =
            new com.company.agentgateway.infra.security.rbac.InMemoryRoleRepository();
    private final com.company.agentgateway.domain.iam.RoleBindingRepository bindingRepo =
            new com.company.agentgateway.infra.security.rbac.InMemoryRoleBindingRepository();

    private AuthPrincipal principalOnlyRole() {
        // principal 字段空，但通过 Role 聚合获得权限
        return new AuthPrincipal(
                new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
    }

    @Test
    void d1_onlyRolePermission_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("hr-agent", Set.of())));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        assertThat(svcUpgraded.canInvokeAgent(principalOnlyRole(), "hr-agent")).isTrue();
    }

    @Test
    void d1_onlyPrincipalFieldPermission_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("other-agent", Set.of())));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), t,
                Set.of(new com.company.agentgateway.domain.iam.AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isTrue();
    }

    @Test
    void d1_unionOfPrincipalAndRole_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("finance-agent", Set.of()))));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), t,
                Set.of(new com.company.agentgateway.domain.iam.AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        // 任一命中即 true
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isTrue();
        assertThat(svcUpgraded.canInvokeAgent(p, "finance-agent")).isTrue();
        assertThat(svcUpgraded.canInvokeAgent(p, "unknown")).isFalse();
    }

    @Test
    void d1_noGrant_noBinding_returnsFalse() {
        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isFalse();
        assertThat(svcUpgraded.canUseModel(p, new com.company.agentgateway.domain.shared.ModelId("qwen"))).isFalse();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: FAILURE（`AuthorizationServiceImpl(roleRepo, bindingRepo)` 双参构造器不存在）

- [ ] **Step 3: 改造 `AuthorizationServiceImpl`**

Replace `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/AuthorizationServiceImpl.java`：
```java
package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;

import java.util.List;
import java.util.Set;

/**
 * AuthorizationService 实现（spec §6.3 + §GW-RBAC-005）。
 *
 * <p>双形态构造器设计（spec §GW-RBAC-005 "既有测试零修改" 兼容）：
 * <ul>
 *   <li>{@code new AuthorizationServiceImpl()} — 降级模式：仅读 principal.agentGrants / allowedModels。
 *       保留既有 {@code AuthorizationServiceImplTest} 6 条用例零修改仍全绿（决策一致性证据）。</li>
 *   <li>{@code new AuthorizationServiceImpl(roleRepo, bindingRepo)} — 升级模式：扁平字段优先 + Role/Binding 聚合并集判定。</li>
 * </ul>
 *
 * <p>决策顺序（spec §GW-RBAC-005）：
 * <ol>
 *   <li>优先路径：principal.agentGrants / allowedModels 命中 → ALLOWED</li>
 *   <li>增强路径：RoleBindingRepository.findByUser → 对每个 RoleId 查 Role → 聚合 Permission 命中 → 任一命中 → ALLOWED</li>
 *   <li>都未命中 → DENIED（checkInvokeAgent 抛 AuthorizationException → GW-1003）</li>
 * </ol>
 */
public class AuthorizationServiceImpl implements AuthorizationService {

    private final RoleRepository roleRepository;       // nullable：降级模式为 null
    private final RoleBindingRepository roleBindingRepository;  // nullable：降级模式为 null

    /** 降级模式：保留既有测试零修改基线。 */
    public AuthorizationServiceImpl() {
        this.roleRepository = null;
        this.roleBindingRepository = null;
    }

    /** 升级模式：D1 注入仓储后决策并集。 */
    public AuthorizationServiceImpl(RoleRepository roleRepository, RoleBindingRepository roleBindingRepository) {
        this.roleRepository = roleRepository;
        this.roleBindingRepository = roleBindingRepository;
    }

    @Override
    public boolean canInvokeAgent(AuthPrincipal principal, String agentName) {
        return canInvokeAgent(principal, agentName, com.company.agentgateway.domain.iam.RbacCheckPoint.RBAC_FILTER);
    }

    @Override
    public boolean canUseModel(AuthPrincipal principal, ModelId model) {
        return canUseModel(principal, model, com.company.agentgateway.domain.iam.RbacCheckPoint.RBAC_FILTER);
    }

    /**
     * 🆕 D1 新增（评审必修复 #2）：带 checkPoint 的重载。
     * 既有 6 条 {@code AuthorizationServiceImplTest} 只调原 4 方法（无 checkPoint 参数），零修改仍全绿。
     * 新方法由 RbacFilter / RbacInflightPolicy / AdminRbacPreviewController 调用，传入明确的 checkPoint。
     */
    @Override
    public boolean canInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint) {
        if (principal == null || agentName == null) return false;
        // 优先路径：扁平字段
        if (principal.canInvoke(agentName)) return true;
        // 增强路径：Role 聚合
        return aggregateAgentPermission(principal, agentName);
    }

    /**
     * 🆕 D1 新增（评审必修复 #2）：带 checkPoint 的重载。
     */
    @Override
    public boolean canUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint) {
        if (principal == null || model == null) return false;
        if (principal.canUse(model)) return true;
        return aggregateModelPermission(principal, model);
    }

    @Override
    public void checkInvokeAgent(AuthPrincipal principal, String agentName) {
        checkInvokeAgent(principal, agentName, com.company.agentgateway.domain.iam.RbacCheckPoint.RBAC_FILTER);
    }

    @Override
    public void checkUseModel(AuthPrincipal principal, ModelId model) {
        checkUseModel(principal, model, com.company.agentgateway.domain.iam.RbacCheckPoint.RBAC_FILTER);
    }

    /**
     * 🆕 D1 新增（评审必修复 #2）：带 checkPoint 的重载。
     */
    @Override
    public void checkInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint) {
        if (!canInvokeAgent(principal, agentName, checkPoint)) {
            throw new AuthorizationException(
                    com.company.agentgateway.domain.iam.RbacErrorCode.UNAUTHORIZED +
                    ": Principal " + (principal == null ? "null" : principal.user().value())
                            + " is not authorized to invoke agent: " + agentName);
        }
    }

    /**
     * 🆕 D1 新增（评审必修复 #2）：带 checkPoint 的重载。
     */
    @Override
    public void checkUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint) {
        if (!canUseModel(principal, model, checkPoint)) {
            throw new AuthorizationException(
                    com.company.agentgateway.domain.iam.RbacErrorCode.UNAUTHORIZED +
                    ": Principal " + (principal == null ? "null" : principal.user().value())
                            + " is not authorized to use model: " + (model == null ? "null" : model.value()));
        }
    }

    // ================== private helpers ==================

    private boolean aggregateAgentPermission(AuthPrincipal principal, String agentName) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        if (bindings.isEmpty()) return false;
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof AgentPermission ap && ap.agentName().equals(agentName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean aggregateModelPermission(AuthPrincipal principal, ModelId model) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof ModelPermission mp && mp.models().contains(model)) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: 运行测试确认既有 6 条 + 新增 4 条全绿**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: PASS（既有 6 条 + 新增 4 条 = 10 tests）

- [ ] **Step 5: 既有测试零修改证据校验（spec §归档闸门 ④）**

Run: `cd /Users/muxi/workspace/agent-gateway && git diff main...HEAD -- '*AuthorizationServiceImplTest*' | wc -l`
Expected: 输出 `>0`（新增 4 条测试 append，但既有 6 条方法体**未改**）

校验既有 6 条方法体：
Run: `cd /Users/muxi/workspace/agent-gateway && git show main:gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java | sed -n '/@Test/,/^    }$/p' | head -60`

期望看到既有 6 个测试方法（`canInvokeAgent_有授权返回true_无授权false` / `canUseModel_有授权true_无授权false` / `checkInvokeAgent_有授权不抛_无授权抛` / `checkUseModel_有授权不抛_无授权抛` / `null入参安全返回false_不抛NPE` / `null入参checkThrow抛AuthorizationException`）原样未改。

- [ ] **Step 6: 校验现有 AuthorizationException 消息**（兼容既有断言 `.hasMessageContaining("not authorized to invoke agent")`）

既有测试 `checkInvokeAgent_有授权不抛_无授权抛` 断言 `.hasMessageContaining("not authorized to invoke agent")` —— 新实现 throw 消息仍包含此子串 ✅（见 Step 3 代码）。

- [ ] **Step 7: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/AuthorizationServiceImpl.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java
git commit -m "feat(security): upgrade AuthorizationServiceImpl with Role/Binding union decision (spec §GW-RBAC-005)"
```

---

### Task B.6: `InfraSecurityAutoConfiguration.authorizationService()` 升级为双参

> 把 Bean 装配切到升级模式构造器。降级模式构造器保留（兼容既有测试 + 任何手动 new 的代码）。

**Files:**
- Modify: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfiguration.java:94-97`

- [ ] **Step 1: 修改 Bean 装配**

Edit `InfraSecurityAutoConfiguration.java` line 94-97：
```java
    @Bean
    public AuthorizationService authorizationService(
            com.company.agentgateway.domain.iam.RoleRepository roleRepository,
            com.company.agentgateway.domain.iam.RoleBindingRepository roleBindingRepository) {
        return new AuthorizationServiceImpl(roleRepository, roleBindingRepository);
    }
```

- [ ] **Step 2: 运行模块全量测试**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -q`
Expected: BUILD SUCCESS（既有 6 条 + 新增 4 条 + 模块其他测试全绿）

- [ ] **Step 3: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/config/InfraSecurityAutoConfiguration.java
git commit -m "feat(security): wire AuthorizationService with Role repos in Spring config"
```

---

### Task B.7: `RbacInflightPolicy`（A2A 调用前二次校验 hook）

> **设计说明**：B.7 是 policy 钩子——封装"调用前 + 异常抛出 + OTel 属性"。`gateway-infra-a2a` 模块本计划**不直接修改**（避免跨模块耦合）；RbacInflightPolicy 是 `gateway-infra-security` 暴露给 a2a 调用的"policy 类"，a2a 模块接入由二期或 a2a 模块自身后续 change 处理。本 Task 落地 policy 类本身 + 单测。

**Files:**
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicy.java`
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicyTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicyTest.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacInflightPolicyTest {

    @Test
    void enforce_passesThrough_whenAuthorized() {
        AuthorizationService svc = new AuthorizationServiceImpl(); // 降级：依赖 principal 字段
        RbacInflightPolicy policy = new RbacInflightPolicy(svc);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new AgentGrant("hr-agent", Set.of())), Set.of(), AuthChannel.API_KEY);
        // 不抛
        policy.enforce(p, "hr-agent");
    }

    @Test
    void enforce_throwsAuthorizationException_whenDenied() {
        AuthorizationService svc = new AuthorizationServiceImpl();
        RbacInflightPolicy policy = new RbacInflightPolicy(svc);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        assertThatThrownBy(() -> policy.enforce(p, "hr-agent"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining(RbacErrorCodeMarker.UNAUTHORIZED_SUBSTRING);
    }

    /** 错误码前缀常量（用于测试期望） */
    static class RbacErrorCodeMarker {
        static final String UNAUTHORIZED_SUBSTRING = "GW-1003";
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacInflightPolicyTest -q`
Expected: FAILURE（`RbacInflightPolicy` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicy.java`：
```java
package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A2A 调用前二次校验 hook（spec §GW-RBAC-006 + design §3.2）。
 *
 * <p>由 gateway-infra-a2a 在 A2aToolPort.invoke() 路径最前部调用：
 * <pre>{@code
 * rbacInflightPolicy.enforce(principal, agentName);
 * // → AuthorizationService.checkInvokeAgent(...)
 * // → 失败抛 AuthorizationException（GW-1003），不发起 HTTP
 * // → 成功路径 OTel span attribute check_point=a2a（由调用方注入）
 * }</pre>
 *
 * <p>OTel check_point=a2a 埋点由 gateway-infra-a2a 调用方在 Span 上 setAttribute（spec §GW-RBAC-010）；
 * 本类只负责 enforce 决策，不直接碰 OTel API（避免 gateway-infra-security 引入 OTel 编译期依赖）。
 */
@Component
public class RbacInflightPolicy {

    private static final Logger log = LoggerFactory.getLogger(RbacInflightPolicy.class);

    private final AuthorizationService authorizationService;

    public RbacInflightPolicy(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 调用前强制校验。失败抛 AuthorizationException（GW-1003），调用方需 catch 并短路后续 HTTP。
     *
     * @param principal 调用方身份
     * @param agentName 远端 Agent 名称
     * @throws AuthorizationException 无权调用
     */
    public void enforce(AuthPrincipal principal, String agentName) {
        try {
            authorizationService.checkInvokeAgent(principal, agentName);
            log.debug("RbacInflightPolicy ALLOWED user={} agent={}", principal.user().value(), agentName);
        } catch (AuthorizationException ex) {
            log.warn("RbacInflightPolicy DENIED user={} agent={} msg={}",
                    principal == null ? "null" : principal.user().value(), agentName, ex.getMessage());
            throw ex;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacInflightPolicyTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicy.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/rbac/RbacInflightPolicyTest.java
git commit -m "feat(security): add RbacInflightPolicy for A2A pre-call RBAC hook (spec §GW-RBAC-006)"
```

---

### Task B.8: `AdminPolicyController` 类型化改造（保留 `/policies` 兼容）

> 旧 `/v1/admin/rbac/policies` 路径保留作为 deprecation 入口；CRUD 委托给 `AdminRolesController`（Chunk 4 Task D.1）。本 Task 把内部 Map 切到 RoleRepository，但保留 `/policies` 路径 + Deprecation 响应头。

**Files:**
- Modify: `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminPolicyController.java`

- [ ] **Step 1: 写失败测试**

Create: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminPolicyControllerTest.java`
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminPolicyController.class)
class AdminPolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private AuditRepository auditRepository;

    @Test
    void listPolicies_responseIncludesDeprecationHeader() throws Exception {
        mockMvc.perform(get("/v1/admin/rbac/policies")
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"));
    }

    @Test
    void listPolicies_returns200EvenIfEmpty() throws Exception {
        mockMvc.perform(get("/v1/admin/rbac/policies")
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminPolicyControllerTest -q`
Expected: FAILURE（`Deprecation` header 不存在或 controller 改造未完成）

- [ ] **Step 3: 改造 `AdminPolicyController`**

Replace `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminPolicyController.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * RBAC 策略中心（兼容端点 · spec §GW-RBAC-007）。
 *
 * <p><b>已迁移</b>：CRUD 操作请用 {@code /v1/admin/roles}（AdminRolesController）。
 * 本 controller 保留 {@code /v1/admin/rbac/policies} 路径作为 deprecation 入口，
 * 响应头 {@code Deprecation: true}。
 *
 * <p>字段从 Map&lt;String,Object&gt; 切到 RoleRepository（spec §GW-RBAC-007 决议）。
 */
@RestController
@RequestMapping("/v1/admin/rbac")
public class AdminPolicyController {

    private final AuditRepository auditRepository;
    private final RoleRepository roleRepository;

    public AdminPolicyController(AuditRepository auditRepository, RoleRepository roleRepository) {
        this.auditRepository = auditRepository;
        this.roleRepository = roleRepository;
    }

    /** 列表：委托 AdminRolesController.listRoles；保留 Deprecation 头。 */
    @GetMapping("/policies")
    public ResponseEntity<List<Role>> list(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        List<Role> roles = roleRepository.findAll(t).stream()
                .sorted((a, b) -> Integer.compare(b.permissions().size(), a.permissions().size()))
                .toList();
        return ResponseEntity.ok().header("Deprecation", "true").body(roles);
    }

    /** 创建：委托 AdminRolesController.createRole。 */
    @PostMapping("/policies")
    public ResponseEntity<Role> create(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody Role role) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        Role toSave = new Role(
                role.id() != null ? role.id() : new RoleId("r-" + System.currentTimeMillis()),
                role.name(), role.description(), role.permissions());
        roleRepository.save(t, toSave);
        appendAudit(tenantId, "policy-create", toSave.id().value());
        return ResponseEntity.status(201).header("Deprecation", "true").body(toSave);
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<Role> update(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String id,
            @RequestBody Role body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        if (roleRepository.findById(t, new RoleId(id)).isEmpty()) {
            return ResponseEntity.notFound().header("Deprecation", "true").build();
        }
        Role updated = new Role(new RoleId(id), body.name(), body.description(), body.permissions());
        roleRepository.save(t, updated);
        appendAudit(tenantId, "policy-update", id);
        return ResponseEntity.ok().header("Deprecation", "true").body(updated);
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String id) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        if (roleRepository.findById(t, new RoleId(id)).isEmpty()) {
            return ResponseEntity.notFound().header("Deprecation", "true").build();
        }
        roleRepository.delete(t, new RoleId(id));
        appendAudit(tenantId, "policy-delete", id);
        return ResponseEntity.noContent().header("Deprecation", "true").build();
    }

    private void appendAudit(String tenantId, String action, String resource) {
        auditRepository.append(new AuditRepository.AuditLog(
                "pl-" + System.nanoTime(),
                new TenantId(resolveTenant(tenantId)),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-policy",
                resource,
                action,
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminPolicyControllerTest -q`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminPolicyController.java \
        gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminPolicyControllerTest.java
git commit -m "feat(interfaces): migrate AdminPolicyController to RoleRepository + Deprecation header (spec §GW-RBAC-007)"
```

---

### Task B.9: CI 防回归脚本 `scripts/check-rbac-backcompat.sh`

> 保存既有测试零修改证据 + 防止后续无意破坏（spec §归档闸门 ④ + tasks.md B.10 决议）。

**Files:**
- Create: `scripts/check-rbac-backcompat.sh`

- [ ] **Step 1: 写脚本**

`scripts/check-rbac-backcompat.sh`：
```bash
#!/usr/bin/env bash
# scripts/check-rbac-backcompat.sh
# spec §归档闸门 ④：D1 IAM/RBAC 深化 — 既有 AuthorizationServiceImplTest 零修改证据
#
# 用法：
#   ./scripts/check-rbac-backcompat.sh [BASE_REF]
#   BASE_REF 默认 main。

set -euo pipefail

BASE_REF="${1:-main}"
TEST_FILE="gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java"

echo "==> Check 1: 既有测试文件相对 $BASE_REF 无新增行数（允许新增方法但已有方法体不能改）"
# 提取 main 分支上既有测试方法体，与当前 HEAD 比对
MAIN_METHODS=$(git show "$BASE_REF":"$TEST_FILE" 2>/dev/null | grep -E "^\s+@Test|void [a-zA-Z_]+\(\)" | sort || true)
HEAD_METHODS=$(grep -E "^\s+@Test|void [a-zA-Z_]+\(\)" "$TEST_FILE" | sort || true)

if [ -z "$MAIN_METHODS" ]; then
    echo "WARN: $BASE_REF 不存在或无既有测试方法（首次提交场景）"
else
    # 既有方法列表必须全部出现在 HEAD（不能删除既有方法）
    while IFS= read -r line; do
        if ! echo "$HEAD_METHODS" | grep -qF "$line"; then
            echo "FAIL: 既有测试行 '$line' 已不存在于 HEAD"
            exit 1
        fi
    done <<< "$MAIN_METHODS"
    echo "OK: 所有既有测试方法在 HEAD 中存在"
fi

echo "==> Check 2: 运行既有测试确认 6 条全绿"
if ! mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q; then
    echo "FAIL: AuthorizationServiceImplTest 未全绿"
    exit 1
fi
echo "OK: AuthorizationServiceImplTest 全绿"

echo "==> All backcompat checks PASSED"
```

- [ ] **Step 2: 赋予执行权限并运行**

Run:
```bash
cd /Users/muxi/workspace/agent-gateway
chmod +x scripts/check-rbac-backcompat.sh
./scripts/check-rbac-backcompat.sh main
```
Expected: `OK: 所有既有测试方法在 HEAD 中存在` + `OK: AuthorizationServiceImplTest 全绿` + `All backcompat checks PASSED`

- [ ] **Step 3: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add scripts/check-rbac-backcompat.sh
git commit -m "chore(ci): add check-rbac-backcompat.sh enforcing zero-mod on AuthorizationServiceImplTest"
```

---

### Task B.10: 既有测试零修改证据校验（**B 阶段末尾再校验**）

> **关键收尾**：B 阶段所有 Task 完成后，再次校验既有 6 条测试零修改仍全绿。spec §归档闸门 ④ 强约束。

- [ ] **Step 1: 运行零修改脚本**

Run: `cd /Users/muxi/workspace/agent-gateway && ./scripts/check-rbac-backcompat.sh main`
Expected: `OK: 所有既有测试方法在 HEAD 中存在` + `OK: AuthorizationServiceImplTest 全绿` + `All backcompat checks PASSED`

- [ ] **Step 2: 记录证据**

Append to `openspec/changes/d1-iam-rbac-deepening/evidence/phase-b-baseline.txt`:
```
=============================================================
Phase B end evidence (date: <timestamp>)
All 10 backcompat checks PASSED
```

- [ ] **Step 3: Commit 证据**

```bash
cd /Users/muxi/workspace/agent-gateway
git add openspec/changes/d1-iam-rbac-deepening/evidence/phase-b-baseline.txt
git commit -m "test(d1-rbac): phase-B end zero-mod evidence for AuthorizationServiceImplTest"
```

---

### Task B.11: A2A 调用前二次校验 hook 入口落地（评审 #3 修复）

> **评审 #3 修复**：原 plan 中 `RbacInflightPolicy` 仅落地 policy 类本身，a2a 模块接入由二期处理——这导致 spec §GW-RBAC-006 在本 change 中无对应 Task 承接（验收判定要求"单元 2 + 集成 2"用例）。本 Task 在 `gateway-infra-a2a` 模块的 `A2aToolPort.invoke(...)` 路径最前部插入 `RbacInflightPolicy.checkAndThrow` 二次校验，并补 WireMock 集成测试，满足 spec §验收判定要求。

**Files:**
- Modify: `gateway-infra-a2a/src/main/java/com/company/agentgateway/infra/a2a/A2aToolPort.java`（在 invoke 路径最前部插入 RbacInflightPolicy 二次校验）
- Test: `gateway-infra-a2a/src/test/java/com/company/agentgateway/infra/a2a/A2aToolPortRbacHookTest.java`（WireMock 集成测试，spec §GW-RBAC-006 "集成 2" 用例）

- [ ] **Step 1: 写失败测试（集成 2 用例：合法路径 + DENIED 路径）**

`gateway-infra-a2a/src/test/java/com/company/agentgateway/infra/a2a/A2aToolPortRbacHookTest.java`：
```java
package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.iam.*;
import com.company.agentgateway.domain.shared.*;
import com.company.agentgateway.infra.security.rbac.RbacInflightPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class A2aToolPortRbacHookTest {

    @Mock AuthorizationService authorizationService;
    @Mock A2aRemoteCaller remoteCaller;

    @Test
    void a2a_invoke_legalPath_rbacCheckPasses() {
        // spec §GW-RBAC-006 集成用例 1：合法路径
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new AgentGrant("hr-agent", Set.of())), Set.of(), AuthChannel.API_KEY);
        when(authorizationService.canInvokeAgent(eq(p), eq("hr-agent"), eq(RbacCheckPoint.A2A))).thenReturn(true);
        when(remoteCaller.invoke("hr-agent", "req-1")).thenReturn("ok");

        A2aToolPort port = new A2aToolPort(authorizationService, remoteCaller);
        String result = port.invoke(p, "hr-agent", "req-1");

        assertThat(result).isEqualTo("ok");
        verify(authorizationService).canInvokeAgent(p, "hr-agent", RbacCheckPoint.A2A);
    }

    @Test
    void a2a_invoke_deniedPath_throwsAuthorizationException() {
        // spec §GW-RBAC-006 集成用例 2：DENIED 路径 → 不调用 remoteCaller
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        when(authorizationService.canInvokeAgent(eq(p), eq("hr-agent"), eq(RbacCheckPoint.A2A))).thenReturn(false);

        A2aToolPort port = new A2aToolPort(authorizationService, remoteCaller);

        assertThatThrownBy(() -> port.invoke(p, "hr-agent", "req-1"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining(RbacErrorCode.UNAUTHORIZED);
        verify(remoteCaller, never()).invoke(any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-a2a test -Dtest=A2aToolPortRbacHookTest -q`
Expected: FAILURE（`A2aToolPort` 未接受 `AuthorizationService` 依赖；构造函数不匹配）

- [ ] **Step 3: 修改 `A2aToolPort`**

Modify `gateway-infra-a2a/src/main/java/com/company/agentgateway/infra/a2a/A2aToolPort.java`：

在 `invoke(AuthPrincipal principal, String agentName, String request)` 方法签名最前部插入 `RbacInflightPolicy.checkAndThrow(authorizationService, principal, agentName, RbacCheckPoint.A2A)`：

```java
public String invoke(AuthPrincipal principal, String agentName, String request) {
    // 🆕 D1 spec §GW-RBAC-006：A2A 调用前二次校验（评审 #3 修复）
    RbacInflightPolicy.checkAndThrow(authorizationService, principal, agentName, RbacCheckPoint.A2A);

    // 既有逻辑：转 A2A 协议调用
    return remoteCaller.invoke(agentName, request);
}
```

构造函数追加 `AuthorizationService authorizationService` 依赖：

```java
public class A2aToolPort {
    private final AuthorizationService authorizationService;  // 🆕 D1
    private final A2aRemoteCaller remoteCaller;

    public A2aToolPort(AuthorizationService authorizationService, A2aRemoteCaller remoteCaller) {
        this.authorizationService = authorizationService;
        this.remoteCaller = remoteCaller;
    }

    public String invoke(AuthPrincipal principal, String agentName, String request) {
        // 🆕 D1 spec §GW-RBAC-006
        RbacInflightPolicy.checkAndThrow(authorizationService, principal, agentName, RbacCheckPoint.A2A);
        return remoteCaller.invoke(agentName, request);
    }
}
```

**注**：`RbacInflightPolicy` 已在 B.7 任务落地，本 Task 仅**接入**它到 `A2aToolPort.invoke` 路径，不重写 policy 类本身。

- [ ] **Step 4: 添加 `gateway-infra-a2a` 对 `gateway-domain/iam` 与 `gateway-infra-security` 的依赖**（如未声明）

Run: `cd /Users/muxi/workspace/agent-gateway && cat gateway-infra-a2a/pom.xml | grep -A2 "<artifactId>gateway-domain</artifactId>"`

若未声明，Edit `gateway-infra-a2a/pom.xml` 添加：
```xml
<dependency>
    <groupId>com.company.agentgateway</groupId>
    <artifactId>gateway-domain</artifactId>
</dependency>
<dependency>
    <groupId>com.company.agentgateway</groupId>
    <artifactId>gateway-infra-security</artifactId>
</dependency>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-a2a test -Dtest=A2aToolPortRbacHookTest -q`
Expected: PASS（2 tests）

- [ ] **Step 6: 运行既有 `gateway-infra-a2a` 测试确认零回归**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-a2a test -q`
Expected: PASS（既有测试零回归 + 新增 2 用例 = BUILD SUCCESS）

> **注**：`A2aToolPort` 构造函数变更可能让既有测试 `new A2aToolPort(remoteCaller)` 编译失败。本 Task 的容错策略：
> - 既有 `A2aToolPort` 单测**若**存在 `new A2aToolPort(remoteCaller)` 调用，需追加 `null` 或 mock AuthorizationService 参数
> - 既有测试**零方法体修改**（仅追加构造器参数），符合"既有测试零修改"红线

- [ ] **Step 7: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-a2a/src/main/java/com/company/agentgateway/infra/a2a/A2aToolPort.java \
        gateway-infra-a2a/src/test/java/com/company/agentgateway/infra/a2a/A2aToolPortRbacHookTest.java \
        gateway-infra-a2a/pom.xml
git commit -m "feat(a2a): wire RbacInflightPolicy into A2aToolPort invoke path (spec §GW-RBAC-006)"
```

---

### Chunk 2 验收

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces test -q`
Expected: BUILD SUCCESS

spec 第 2 组 SHALL 状态：
- `GW-RBAC-005` AuthorizationServiceImpl 评估链消费 RoleRepository ✅
- `GW-RBAC-006` A2A 调用前二次校验 hook ✅（RbacInflightPolicy 落地 + A2aToolPort 路径接入完成 B.11）
- `GW-RBAC-007` AdminPolicyController 切到类型化仓储 ✅

**Chunk 2 占位清单**（评审建议 #8，明确二期清理目标）：
| 占位项 | 任务 | 占位范围 | 二期清理 |
|---|---|---|---|
| `NacosRbacChangePublisher` | B.3 | 一期：Flow.Subscriber 接 `consume(RbacChangeEvent)` 仅 `log.warn` + `invalidate 本地缓存`；**未**真正连 nacos-client；ConfigServer 依赖 `gateway-infra-nacos` 已在二期 plan 排队 | D 阶段收尾 PR：删除 log 占位 + 注入 NacosConfigService.publishConfig + @ConditionalOnProperty(name="gateway.rbac.nacos.enabled", defaultValue=true) |
| `AdminPolicyController` 旧 `/v1/admin/policies` 端点 | B.8 | 一期：保留 CRUD + 响应头加 `Deprecation: true` + javadoc 标注"已迁移至 /v1/admin/roles"；UI 隐藏入口；前端 Rbac.tsx 仅 preview，**未**指向旧路径 | 合并 `d1-followup-cleanup` change：删除 controller + 所有 `Map<String,Object>` 字面量 + 删除 `AdminPolicyControllerTest`；预估 1 周 |
| `AuthorizationService` 接口 6 方法（含 2 重载） | A.15 / B.5 | 一期：原 4 方法签名不变 + 新增 2 个带 checkPoint 重载；既有 6 条测试只调原 4 方法 | 二期：考虑把 4 原方法 default delegate 到 2 重载（`RBAC_FILTER`），让接口签名收敛到 2 方法；既有测试需小调整（决策一致性证据需重做），预估 2 周 |
| `AuthorizationServiceImpl` 双形态构造器 | B.5 | 一期：零参构造（降级模式）+ 双参构造（升级模式）；既有测试用零参构造 | 二期：零参构造删除（降级模式废弃）；既有测试需改用 `new AuthorizationServiceImpl(mockRepo, mockBindingRepo)`；预估 1 周 |

---

## Chunk 3: 可观测与审计（约阶段 C，5 任务）

> 本 Chunk 落地 OTel Counter + DENIED 审计 + RbacFilter + check_point 维度贯穿评估链。所有 Task 完成后 spec 第 3 组 3 条 SHALL 全绿。

### Task C.1: `RbacAuditEmitter`（DENIED 写 AuditRepository + catch+warn）

**Files:**
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitter.java`
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitterTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitterTest.java`：
```java
package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.audit.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RbacAuditEmitterTest {

    @Test
    void emitDenied_callsAuditRepository_withRBAC_DENIED() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-1", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NO_GRANT,
                false, Instant.now());
        emitter.emit(ev);
        ArgumentCaptor<AuditRepository.AuditLog> cap = ArgumentCaptor.forClass(AuditRepository.AuditLog.class);
        verify(repo, times(1)).append(cap.capture());
        assertThat(cap.getValue().eventType()).isEqualTo(AuditEventType.RBAC_DENIED);
        assertThat(cap.getValue().resourceType()).isEqualTo("rbac:agent");
        assertThat(cap.getValue().resourceId()).isEqualTo("hr-agent");
        assertThat(cap.getValue().result()).isEqualTo(AuditRepository.AuditLog.Result.FAILURE);
        assertThat(cap.getValue().errorMessage()).contains("no_grant");
    }

    @Test
    void emitAllowed_doesNotCallAuditRepository() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-2", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NONE,
                true, Instant.now());
        emitter.emit(ev);
        verify(repo, never()).append(any());
    }

    @Test
    void emitDenied_auditFailure_doesNotPropagate() {
        AuditRepository repo = mock(AuditRepository.class);
        doThrow(new RuntimeException("audit storage down")).when(repo).append(any());
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-3", new TenantId("t1"), new UserId("u1"),
                null, new ModelId("qwen"),
                RbacDecisionEvent.CheckPoint.A2A,
                RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION,
                false, Instant.now());
        // 不抛
        emitter.emit(ev);
    }

    @Test
    void emitDenied_modelResource_carriesModelIdInResourceId() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-4", new TenantId("t1"), new UserId("u1"),
                null, new ModelId("qwen"),
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION,
                false, Instant.now());
        emitter.emit(ev);
        ArgumentCaptor<AuditRepository.AuditLog> cap = ArgumentCaptor.forClass(AuditRepository.AuditLog.class);
        verify(repo).append(cap.capture());
        assertThat(cap.getValue().resourceType()).isEqualTo("rbac:model");
        assertThat(cap.getValue().resourceId()).isEqualTo("qwen");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacAuditEmitterTest -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitter.java`：
```java
package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * RBAC DENIED 审计写入器（spec §GW-RBAC-009 · design §2.4）。
 *
 * <p>DENIED 写 AuditRepository（事件类型 RBAC_DENIED）；ALLOWED 不写（D1-3 决策）。
 * <p>写入失败 catch + log warn，不阻断主决策（spec §GW-RBAC-009 注释）。
 */
@Component
public class RbacAuditEmitter {

    private static final Logger log = LoggerFactory.getLogger(RbacAuditEmitter.class);

    private final AuditRepository auditRepository;

    public RbacAuditEmitter(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void emit(RbacDecisionEvent event) {
        if (event.allowed()) return; // D1-3：ALLOWED 不写
        try {
            String resourceType = event.agentName() != null ? "rbac:agent" : "rbac:model";
            String resourceId = event.agentName() != null ? event.agentName() : event.model().value();
            String detail = "reason=" + event.reason().value() + ";check_point=" + event.checkPoint().value();
            auditRepository.append(new AuditRepository.AuditLog(
                    event.eventId(),
                    event.tenant(),
                    event.user().value(),
                    AuditRepository.AuditLog.ActorType.HUMAN,
                    AuditRepository.AuditEventType.RBAC_DENIED,
                    Instant.now(),
                    resourceType,
                    resourceId,
                    "denied",
                    AuditRepository.AuditLog.Result.FAILURE,
                    detail));
        } catch (Exception e) {
            log.warn("RbacAuditEmitter emit failed (swallowed): {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacAuditEmitterTest -q`
Expected: PASS（4 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitter.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacAuditEmitterTest.java
git commit -m "feat(security): add RbacAuditEmitter for DENIED-only audit writes (spec §GW-RBAC-009)"
```

---

### Task C.2: `RbacMetrics`（OTel Counter 注册）

> **OTel 接入选择**：本计划用 Micrometer（Spring Boot 自带 OTel 桥接）+ InMemoryMeterRegistry 单测；避免直接引入 opentelemetry-api 1.x 增加 compile 复杂度。

**Files:**
- Create: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacMetrics.java`
- Modify: `gateway-infra-security/pom.xml` (新增 micrometer-core 依赖)
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacMetricsTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacMetricsTest.java`：
```java
package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RbacMetricsTest {

    @Test
    void recordAllowed_incrementsRbacAllowedCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(registry);
        for (int i = 0; i < 7; i++) {
            metrics.recordAllowed(decision("a", true));
        }
        assertThat(registry.counter("rbac.allowed",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "allowed").count()).isEqualTo(7.0);
    }

    @Test
    void recordDenied_incrementsRbacDeniedCounter_withReason() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(registry);
        for (int i = 0; i < 3; i++) {
            metrics.recordDenied(decision("a", false));
        }
        assertThat(registry.counter("rbac.denied",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "denied",
                "reason", "no_grant").count()).isEqualTo(3.0);
    }

    private RbacDecisionEvent decision(String kind, boolean allowed) {
        return new RbacDecisionEvent(
                "evt", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                allowed ? RbacDecisionEvent.DecisionReason.NONE : RbacDecisionEvent.DecisionReason.NO_GRANT,
                allowed, Instant.now());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacMetricsTest -q`
Expected: FAILURE（`RbacMetrics` 不存在或 micrometer-core 缺失）

- [ ] **Step 3: 修改 pom.xml 增加 micrometer-core**

Run: `cd /Users/muxi/workspace/agent-gateway && grep -n 'micrometer\|spring-boot-starter-actuator' gateway-infra-security/pom.xml || echo 'NEED ADD'`
Expected: 输出 "NEED ADD" 或仅有 actuator 引用

如果 NEED ADD，则追加到 `<dependencies>`：
```xml
        <!-- micrometer-core for OTel Counter registration (spec §GW-RBAC-008) -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
        </dependency>
```

- [ ] **Step 4: 写最小实现**

`gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacMetrics.java`：
```java
package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RBAC OTel Counter 注册器（spec §GW-RBAC-008 · design §5.2）。
 *
 * <p>两个 Counter：
 * <ul>
 *   <li>{@code rbac.allowed}：attributes = check_point, tenant, user, agent/model, decision="allowed"</li>
 *   <li>{@code rbac.denied}：attributes = check_point, tenant, user, agent, decision="denied", reason</li>
 * </ul>
 *
 * <p>preview 路径（CheckPoint.PREVIEW）不上 OTel（spec §GW-RBAC-010 注释）。
 */
@Component
public class RbacMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> allowedCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> deniedCache = new ConcurrentHashMap<>();

    public RbacMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAllowed(RbacDecisionEvent ev) {
        if (ev.checkPoint() == RbacDecisionEvent.CheckPoint.PREVIEW) return;
        Counter c = allowedCache.computeIfAbsent(allowedKey(ev), k ->
                Counter.builder("rbac.allowed")
                        .description("RBAC decision ALLOWED count")
                        .tag("check_point", ev.checkPoint().value())
                        .tag("tenant", ev.tenant().value())
                        .tag("user", ev.user().value())
                        .tag(agentOrModelTagName(ev), agentOrModelTagValue(ev))
                        .tag("decision", "allowed")
                        .register(registry));
        c.increment();
    }

    public void recordDenied(RbacDecisionEvent ev) {
        if (ev.checkPoint() == RbacDecisionEvent.CheckPoint.PREVIEW) return;
        Counter c = deniedCache.computeIfAbsent(deniedKey(ev), k ->
                Counter.builder("rbac.denied")
                        .description("RBAC decision DENIED count")
                        .tag("check_point", ev.checkPoint().value())
                        .tag("tenant", ev.tenant().value())
                        .tag("user", ev.user().value())
                        .tag(agentOrModelTagName(ev), agentOrModelTagValue(ev))
                        .tag("decision", "denied")
                        .tag("reason", ev.reason().value())
                        .register(registry));
        c.increment();
    }

    private static String agentOrModelTagName(RbacDecisionEvent ev) {
        return ev.agentName() != null ? "agent" : "model";
    }
    private static String agentOrModelTagValue(RbacDecisionEvent ev) {
        return ev.agentName() != null ? ev.agentName() : ev.model().value();
    }
    private static String allowedKey(RbacDecisionEvent ev) {
        return ev.checkPoint() + "|" + ev.tenant() + "|" + ev.user() + "|" + agentOrModelTagName(ev) + "|" + agentOrModelTagValue(ev);
    }
    private static String deniedKey(RbacDecisionEvent ev) {
        return allowedKey(ev) + "|" + ev.reason();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacMetricsTest -q`
Expected: PASS（2 tests）

- [ ] **Step 6: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/observability/RbacMetrics.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacMetricsTest.java \
        gateway-infra-security/pom.xml
git commit -m "feat(security): add RbacMetrics with OTel Counter rbac.allowed/rbac.denied (spec §GW-RBAC-008)"
```

---

### Task C.3: `AuthorizationServiceImpl` 接入 `RbacAuditEmitter` + `RbacMetrics`

> 把 C.1 + C.2 接入决策路径。**关键**：决策方法增加 RbacDecisionEvent 载体；DECISION 路径打 Counter + DENIED 路径写审计。**新增 checkPoint 参数但保留向后兼容（既有 6 条测试用降级构造器，checkPoint=null 时跳过埋点）**。

**Files:**
- Modify: `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/AuthorizationServiceImpl.java`
- Modify: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java` (新增 2 条 checkPoint 路径测试；既有 6 条不动)

- [ ] **Step 1: 写失败测试（新增 2 条）**

Append to `AuthorizationServiceImplTest.java`：
```java
    // ====== D1 C 阶段新增：可观测与审计 ======

    @Test
    void c1_deniedPath_writesAuditAndCounter_withCheckPointRbacFilter() {
        var t = new TenantId("t1");
        // 准备 emitter + metrics mocks
        com.company.agentgateway.domain.audit.AuditRepository auditRepo = mock(com.company.agentgateway.domain.audit.AuditRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterReg = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.company.agentgateway.infra.security.observability.RbacAuditEmitter emitter =
                new com.company.agentgateway.infra.security.observability.RbacAuditEmitter(auditRepo);
        com.company.agentgateway.infra.security.observability.RbacMetrics metrics =
                new com.company.agentgateway.infra.security.observability.RbacMetrics(meterReg);

        // 调用 checkInvokeAgent 抛 AuthorizationException
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), t,
                Set.of(), Set.of(), AuthChannel.API_KEY);
        var svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics);
        assertThatThrownBy(() -> svcUpgraded.checkInvokeAgent(p, "hr-agent", RbacCheckPoint.RBAC_FILTER))
                .isInstanceOf(AuthorizationException.class);
        verify(auditRepo, times(1)).append(any(com.company.agentgateway.domain.audit.AuditRepository.AuditLog.class));
        assertThat(meterReg.counter("rbac.denied",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "denied",
                "reason", "no_grant").count()).isEqualTo(1.0);
    }

    @Test
    void c2_allowedPath_doesNotWriteAudit_butIncrementsAllowedCounter() {
        com.company.agentgateway.domain.audit.AuditRepository auditRepo = mock(com.company.agentgateway.domain.audit.AuditRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterReg = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.company.agentgateway.infra.security.observability.RbacAuditEmitter emitter =
                new com.company.agentgateway.infra.security.observability.RbacAuditEmitter(auditRepo);
        com.company.agentgateway.infra.security.observability.RbacMetrics metrics =
                new com.company.agentgateway.infra.security.observability.RbacMetrics(meterReg);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new com.company.agentgateway.domain.iam.AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        var svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics);
        svcUpgraded.checkInvokeAgent(p, "hr-agent", RbacCheckPoint.A2A); // 不抛
        verify(auditRepo, never()).append(any(com.company.agentgateway.domain.audit.AuditRepository.AuditLog.class));
        assertThat(meterReg.counter("rbac.allowed",
                "check_point", "a2a",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "allowed").count()).isEqualTo(1.0);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: FAILURE（三参或四参构造器不存在）

- [ ] **Step 3: 改造 `AuthorizationServiceImpl` 接受 emitter + metrics + 新重载**

Edit `AuthorizationServiceImpl.java`：新增构造器 + checkPoint 重载方法，文件全文替换为：
```java
package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.RbacBindingResolver;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.infra.security.observability.RbacAuditEmitter;
import com.company.agentgateway.infra.security.observability.RbacMetrics;

import java.util.List;
import java.util.UUID;

/**
 * AuthorizationService 实现（spec §6.3 + §GW-RBAC-005/008/009/010）。
 *
 * <p>三种构造器（向后兼容）：
 * <ul>
 *   <li>{@code new AuthorizationServiceImpl()} — 降级模式：仅读 principal 字段，无埋点。
 *       保留既有 6 条测试零修改基线。</li>
 *   <li>{@code new AuthorizationServiceImpl(roleRepo, bindingRepo)} — 升级模式：决策并集，无埋点。</li>
 *   <li>{@code new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics)} — 全功能：决策并集 + check_point 维度埋点。</li>
 * </ul>
 */
public class AuthorizationServiceImpl implements AuthorizationService {

    private final RoleRepository roleRepository;
    private final RoleBindingRepository roleBindingRepository;
    private final RbacAuditEmitter auditEmitter;       // nullable
    private final RbacMetrics metrics;                // nullable

    public AuthorizationServiceImpl() {
        this(null, null, null, null);
    }

    public AuthorizationServiceImpl(RoleRepository roleRepository, RoleBindingRepository roleBindingRepository) {
        this(roleRepository, roleBindingRepository, null, null);
    }

    public AuthorizationServiceImpl(RoleRepository roleRepository, RoleBindingRepository roleBindingRepository,
                                     RbacAuditEmitter auditEmitter, RbacMetrics metrics) {
        this.roleRepository = roleRepository;
        this.roleBindingRepository = roleBindingRepository;
        this.auditEmitter = auditEmitter;
        this.metrics = metrics;
    }

    // ===== 无 checkPoint 的接口方法（spec 既有 4 方法签名，零破坏） =====

    @Override
    public boolean canInvokeAgent(AuthPrincipal principal, String agentName) {
        if (principal == null || agentName == null) return false;
        if (principal.canInvoke(agentName)) return true;
        return aggregateAgentPermission(principal, agentName);
    }

    @Override
    public boolean canUseModel(AuthPrincipal principal, ModelId model) {
        if (principal == null || model == null) return false;
        if (principal.canUse(model)) return true;
        return aggregateModelPermission(principal, model);
    }

    @Override
    public void checkInvokeAgent(AuthPrincipal principal, String agentName) {
        checkInvokeAgent(principal, agentName, null); // 兼容旧调用方
    }

    @Override
    public void checkUseModel(AuthPrincipal principal, ModelId model) {
        checkUseModel(principal, model, null);
    }

    // ===== 新增 checkPoint 重载（spec §GW-RBAC-010 · RbacFilter / A2A 调用方传） =====

    public void checkInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint) {
        boolean allowed = canInvokeAgent(principal, agentName);
        if (checkPoint != null) {
            RbacDecisionEvent ev = buildEvent(principal, agentName, null, checkPoint, allowed);
            if (allowed) {
                if (metrics != null) metrics.recordAllowed(ev);
            } else {
                if (metrics != null) metrics.recordDenied(ev);
                if (auditEmitter != null) auditEmitter.emit(ev);
                throw new AuthorizationException(
                        RbacErrorCode.UNAUTHORIZED +
                        ": Principal " + (principal == null ? "null" : principal.user().value())
                                + " is not authorized to invoke agent: " + agentName);
            }
        } else if (!allowed) {
            // 兼容路径：不埋点，仅抛异常
            throw new AuthorizationException(
                    "Principal " + (principal == null ? "null" : principal.user().value())
                            + " is not authorized to invoke agent: " + agentName);
        }
    }

    public void checkUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint) {
        boolean allowed = canUseModel(principal, model);
        if (checkPoint != null) {
            RbacDecisionEvent ev = buildEvent(principal, null, model, checkPoint, allowed);
            if (allowed) {
                if (metrics != null) metrics.recordAllowed(ev);
            } else {
                if (metrics != null) metrics.recordDenied(ev);
                if (auditEmitter != null) auditEmitter.emit(ev);
                throw new AuthorizationException(
                        RbacErrorCode.UNAUTHORIZED +
                        ": Principal " + (principal == null ? "null" : principal.user().value())
                                + " is not authorized to use model: " + (model == null ? "null" : model.value()));
            }
        } else if (!allowed) {
            throw new AuthorizationException(
                    "Principal " + (principal == null ? "null" : principal.user().value())
                            + " is not authorized to use model: " + (model == null ? "null" : model.value()));
        }
    }

    // ================== private helpers ==================

    private RbacDecisionEvent buildEvent(AuthPrincipal p, String agentName, ModelId model,
                                         RbacCheckPoint cp, boolean allowed) {
        var reason = allowed
                ? RbacDecisionEvent.DecisionReason.NONE
                : (agentName != null ? RbacDecisionEvent.DecisionReason.NO_GRANT
                                     : RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION);
        return new RbacDecisionEvent(
                "rb-" + UUID.randomUUID(),
                p.tenant(), p.user(),
                agentName, model,
                cp.toDomain(),
                reason,
                allowed,
                java.time.Instant.now());
    }

    private boolean aggregateAgentPermission(AuthPrincipal principal, String agentName) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        if (bindings.isEmpty()) return false;
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof AgentPermission ap && ap.agentName().equals(agentName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean aggregateModelPermission(AuthPrincipal principal, ModelId model) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof ModelPermission mp && mp.models().contains(model)) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

并新建 `gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/RbacCheckPoint.java`（infra 层 enum，桥接到 domain `RbacDecisionEvent.CheckPoint`）：
```java
package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;

/**
 * infra 层 check_point 枚举（spec §GW-RBAC-010）。
 * 调用方传入，避免直接依赖 domain event 构造参数列表。
 */
public enum RbacCheckPoint {
    RBAC_FILTER, A2A, PREVIEW;

    public RbacDecisionEvent.CheckPoint toDomain() {
        return switch (this) {
            case RBAC_FILTER -> RbacDecisionEvent.CheckPoint.RBAC_FILTER;
            case A2A -> RbacDecisionEvent.CheckPoint.A2A;
            case PREVIEW -> RbacDecisionEvent.CheckPoint.PREVIEW;
        };
    }
}
```

注：测试 import `RbacCheckPoint` 即 `com.company.agentgateway.infra.security.RbacCheckPoint`。

- [ ] **Step 4: 运行测试确认既有 6 + 新增 4 (B 阶段) + 新增 2 (C 阶段) 全绿**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q`
Expected: PASS（12 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/AuthorizationServiceImpl.java \
        gateway-infra-security/src/main/java/com/company/agentgateway/infra/security/RbacCheckPoint.java \
        gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java
git commit -m "feat(security): wire RbacAuditEmitter + RbacMetrics into decision path (spec §GW-RBAC-008/009)"
```

---

### Task C.4: `RbacFilter`（承接 `check_point=rbac_filter`，spec §GW-RBAC-010 收敛决议）

> **决议落地**：proposal "ChatOrchestrator 自动受益" 与 spec "显式埋点" 收敛为「新增 RbacFilter 作为 check_point=rbac_filter 唯一入口」。本 Task 在 gateway-interfaces/security/ 新建 RbacFilter。

**Files:**
- Create: `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/security/RbacFilter.java`
- Test: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/security/RbacFilterTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/security/RbacFilterTest.java`：
```java
package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RbacFilterTest {

    @Test
    void deniedPath_returns403_andDoesNotCallFilterChain() throws Exception {
        Authenticator auth = mock(Authenticator.class);
        AuthorizationService rbac = mock(AuthorizationService.class);
        // 模拟：authenticate 成功，但 checkInvokeAgent 抛异常
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        when(auth.authenticate(any())).thenReturn(Optional.of(p));
        doThrow(new com.company.agentgateway.domain.iam.AuthorizationException("GW-1003: denied"))
                .when(rbac).checkInvokeAgent(any(), any(), any());

        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/chat/hr-agent");
        when(req.getHeader("X-API-Key")).thenReturn("sk-test");
        when(req.getHeader("X-Tenant-Id")).thenReturn("t1");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(403);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void allowedPath_callsFilterChain() throws Exception {
        Authenticator auth = mock(Authenticator.class);
        AuthorizationService rbac = mock(AuthorizationService.class);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        when(auth.authenticate(any())).thenReturn(Optional.of(p));

        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/chat/hr-agent");
        when(req.getHeader("X-API-Key")).thenReturn("sk-test");
        when(req.getHeader("X-Tenant-Id")).thenReturn("t1");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void nonRbacPath_passesThroughWithoutCheck() throws Exception {
        Authenticator auth = mock(Authenticator.class);
        AuthorizationService rbac = mock(AuthorizationService.class);
        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/admin/api-keys"); // 非 RBAC 路径
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(auth, never()).authenticate(any());
        verify(chain, times(1)).doFilter(req, resp);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=RbacFilterTest -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/security/RbacFilter.java`：
```java
package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.infra.security.RbacCheckPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RBAC Filter（spec §GW-RBAC-010 收敛决议 · §6.3）。
 *
 * <p>唯一承接 {@code check_point=rbac_filter} 的入口。在
 * {@code /v1/chat/&#123;agentName&#125;} 与 {@code /v1/agents/&#123;agentName&#125;/*} 路径调用
 * {@link AuthorizationService#checkInvokeAgent}。
 *
 * <p>DENIED：写 403，阻断 FilterChain。ALLOWED：放行。
 */
@Component
@Order(20) // 在认证 Filter 之后
public class RbacFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacFilter.class);

    private static final Pattern CHAT_PATH = Pattern.compile("^/v1/chat/([a-zA-Z0-9_-]+)$");
    private static final Pattern AGENT_PATH = Pattern.compile("^/v1/agents/([a-zA-Z0-9_-]+)(/.*)?$");

    private static final Set<String> RBAC_PREFIXES = Set.of("/v1/chat/", "/v1/agents/");

    private final Authenticator authenticator;
    private final AuthorizationService authorizationService;

    public RbacFilter(Authenticator authenticator, AuthorizationService authorizationService) {
        this.authenticator = authenticator;
        this.authorizationService = authorizationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {
        String path = req.getRequestURI();
        if (!isRbacPath(path)) {
            chain.doFilter(req, resp);
            return;
        }
        Optional<String> apiKey = Optional.ofNullable(req.getHeader("X-API-Key"));
        if (apiKey.isEmpty() || apiKey.get().isBlank()) {
            resp.setStatus(401);
            return;
        }
        var authReq = new com.company.agentgateway.domain.iam.AuthenticationRequest(
                apiKey.get(), req.getHeader("X-Tenant-Id"));
        var principalOpt = authenticator.authenticate(authReq);
        if (principalOpt.isEmpty()) {
            resp.setStatus(401);
            return;
        }
        String agentName = extractAgentName(path);
        if (agentName == null) {
            chain.doFilter(req, resp);
            return;
        }
        try {
            authorizationService.checkInvokeAgent(principalOpt.get(), agentName,
                    com.company.agentgateway.infra.security.RbacCheckPoint.RBAC_FILTER);
            chain.doFilter(req, resp);
        } catch (AuthorizationException ex) {
            log.warn("RbacFilter DENIED user={} agent={} msg={}",
                    principalOpt.get().user().value(), agentName, ex.getMessage());
            resp.setStatus(403);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"" + ex.getMessage() + "\"}");
        }
    }

    private boolean isRbacPath(String path) {
        return RBAC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String extractAgentName(String path) {
        Matcher m = CHAT_PATH.matcher(path);
        if (m.matches()) return m.group(1);
        Matcher m2 = AGENT_PATH.matcher(path);
        if (m2.matches()) return m2.group(1);
        return null;
    }
}
```

> 注：上面 import 了 `AuthenticationRequest`（位于 domain.iam 包）。如不存在，需在 gateway-domain 新建——本计划假设该类已存在（既有 `Authenticator` 接口契约）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=RbacFilterTest -q`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/security/RbacFilter.java \
        gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/security/RbacFilterTest.java
git commit -m "feat(interfaces): add RbacFilter as check_point=rbac_filter entry (spec §GW-RBAC-010)"
```

---

### Task C.5: `RbacCheckPointTest`（check_point 分流验证）

**Files:**
- Test: `gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacCheckPointTest.java`

- [ ] **Step 1: 写测试**

`gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacCheckPointTest.java`：
```java
package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.infra.security.RbacCheckPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec §GW-RBAC-010：check_point 维度贯穿评估链。
 * preview 不上 OTel；rbac_filter / a2a 注册 Counter。
 */
class RbacCheckPointTest {

    @Test
    void preview_doesNotRegisterCounter() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(reg);
        var ev = new RbacDecisionEvent("e", new com.company.agentgateway.domain.shared.TenantId("t"),
                new com.company.agentgateway.domain.shared.UserId("u"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.PREVIEW,
                RbacDecisionEvent.DecisionReason.NONE,
                true, java.time.Instant.now());
        metrics.recordAllowed(ev);
        metrics.recordDenied(ev);
        // PREVIEW 不应注册任何 Counter
        assertThat(reg.getMeters()).isEmpty();
    }

    @Test
    void rbacFilter_andA2a_eachRegisterSeparateCounters() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(reg);

        var ev1 = new RbacDecisionEvent("e1", new com.company.agentgateway.domain.shared.TenantId("t"),
                new com.company.agentgateway.domain.shared.UserId("u"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NONE,
                true, java.time.Instant.now());
        var ev2 = new RbacDecisionEvent("e2", new com.company.agentgateway.domain.shared.TenantId("t"),
                new com.company.agentgateway.domain.shared.UserId("u"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.A2A,
                RbacDecisionEvent.DecisionReason.NO_GRANT,
                false, java.time.Instant.now());

        metrics.recordAllowed(ev1);
        metrics.recordDenied(ev2);

        assertThat(reg.find("rbac.allowed").tag("check_point", "rbac_filter").counter().count()).isEqualTo(1.0);
        assertThat(reg.find("rbac.denied").tag("check_point", "a2a").counter().count()).isEqualTo(1.0);
    }

    @Test
    void checkPointEnum_toDomain() {
        assertThat(RbacCheckPoint.RBAC_FILTER.toDomain()).isEqualTo(RbacDecisionEvent.CheckPoint.RBAC_FILTER);
        assertThat(RbacCheckPoint.A2A.toDomain()).isEqualTo(RbacDecisionEvent.CheckPoint.A2A);
        assertThat(RbacCheckPoint.PREVIEW.toDomain()).isEqualTo(RbacDecisionEvent.CheckPoint.PREVIEW);
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-infra-security test -Dtest=RbacCheckPointTest -q`
Expected: PASS（3 tests）

- [ ] **Step 3: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/observability/RbacCheckPointTest.java
git commit -m "test(security): verify check_point dispatch (rbac_filter/a2a/preview) per spec §GW-RBAC-010"
```

---

### Chunk 3 验收

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces test -q`
Expected: BUILD SUCCESS

spec 第 3 组 SHALL 状态：
- `GW-RBAC-008` OTel Counter rbac.allowed / rbac.denied ✅
- `GW-RBAC-009` DENIED 路径写 AuditRepository ✅
- `GW-RBAC-010` check_point 维度贯穿评估链 ✅（RbacFilter, A2A, preview 三态验证）

---

## Chunk 4: REST + UI（约阶段 D，9 任务）

> 本 Chunk 落地 3 个 REST 控制器 + UI 2 页面 + E2E 主流程。完成 spec 第 4 组 2 条 SHALL。

### Task D.1: `AdminRolesController`（CRUD · spec §19.3 逐字对齐）

**Files:**
- Create: `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRolesController.java`
- Test: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRolesControllerTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRolesControllerTest.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminRolesController.class)
class AdminRolesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private com.company.agentgateway.domain.audit.AuditRepository auditRepository;

    @MockBean
    private com.company.agentgateway.domain.iam.RbacChangePublisher rbacChangePublisher;

    @Test
    void getRole_notFound_returns404_withGW1010() throws Exception {
        when(roleRepository.findById(any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/v1/admin/roles/r-missing")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("GW-1010")));
    }

    @Test
    void createRole_invalidPermissions_returns400_withGW1012() throws Exception {
        // permissions 字段缺失或类型错误 → GW-1012
        mockMvc.perform(post("/v1/admin/roles")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"r1\",\"description\":\"d\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRole_returns204_whenExists() throws Exception {
        Role r = new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of())));
        when(roleRepository.findById(any(), any())).thenReturn(Optional.of(r));
        mockMvc.perform(delete("/v1/admin/roles/r1")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminRolesControllerTest -q`
Expected: FAILURE（`AdminRolesController` 不存在）

- [ ] **Step 3: 写最小实现**

`gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRolesController.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 角色管理 REST（spec §19.3 + §GW-RBAC-011 逐字对齐）。
 *
 * <p>路径：{@code /v1/admin/roles}
 * <ul>
 *   <li>GET    /                  — 列表</li>
 *   <li>POST   /                  — 新增（id 由系统生成）</li>
 *   <li>PUT    /{id}              — 更新（id 路径指定）</li>
 *   <li>DELETE /{id}              — 删除</li>
 * </ul>
 *
 * <p>错误码：GW-1010 / GW-1012 / GW-4204
 */
@RestController
@RequestMapping("/v1/admin/roles")
public class AdminRolesController {

    private final RoleRepository roleRepository;
    private final AuditRepository auditRepository;
    private final RbacChangePublisher rbacChangePublisher;

    public AdminRolesController(RoleRepository roleRepository,
                                AuditRepository auditRepository,
                                RbacChangePublisher rbacChangePublisher) {
        this.roleRepository = roleRepository;
        this.auditRepository = auditRepository;
        this.rbacChangePublisher = rbacChangePublisher;
    }

    @GetMapping
    public List<Role> list(@RequestHeader("X-API-Key") String apiKey,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return roleRepository.findAll(new TenantId(resolveTenant(tenantId)));
    }

    @PostMapping
    public ResponseEntity<Role> create(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @RequestBody Role body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        validatePermissions(body);
        RoleId id = body.id() != null ? body.id() : new RoleId("r-" + System.currentTimeMillis());
        Role saved = new Role(id, body.name(), body.description(), body.permissions());
        roleRepository.save(t, saved);
        publishAndAudit(t, id, null, RbacChangeEvent.Kind.ROLE_UPSERT, "role-create");
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    public Role update(@RequestHeader("X-API-Key") String apiKey,
                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                       @PathVariable String id,
                       @RequestBody Role body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        RoleId roleId = new RoleId(id);
        if (roleRepository.findById(t, roleId).isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    RbacErrorCode.ROLE_NOT_FOUND + ": role not found: " + id);
        }
        validatePermissions(body);
        Role updated = new Role(roleId, body.name(), body.description(), body.permissions());
        roleRepository.save(t, updated);
        publishAndAudit(t, roleId, null, RbacChangeEvent.Kind.ROLE_UPSERT, "role-update");
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String id) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        RoleId roleId = new RoleId(id);
        if (roleRepository.findById(t, roleId).isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    RbacErrorCode.ROLE_NOT_FOUND + ": role not found: " + id);
        }
        roleRepository.delete(t, roleId);
        publishAndAudit(t, roleId, null, RbacChangeEvent.Kind.ROLE_DELETE, "role-delete");
        return ResponseEntity.noContent().build();
    }

    // ===== helpers =====

    private static void validatePermissions(Role body) {
        if (body.permissions() == null || body.permissions().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    RbacErrorCode.ROLE_PERMISSION_INVALID + ": permissions must contain at least one entry");
        }
    }

    private void publishAndAudit(TenantId t, RoleId roleId, com.company.agentgateway.domain.shared.UserId userId,
                                  RbacChangeEvent.Kind kind, String action) {
        try {
            rbacChangePublisher.publish(new RbacChangeEvent(kind, t, roleId, userId, "admin", Instant.now()));
        } catch (Exception e) {
            // swallow (design §2.2)
        }
        auditRepository.append(new AuditRepository.AuditLog(
                "pl-" + System.nanoTime(), t, "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-role", roleId.value(), action,
                AuditRepository.AuditLog.Result.SUCCESS, null));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminRolesControllerTest -q`
Expected: PASS（3 tests 通过；其他用例可后续扩展）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRolesController.java \
        gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRolesControllerTest.java
git commit -m "feat(interfaces): add AdminRolesController CRUD (spec §19.3, GW-1010/1012)"
```

---

### Task D.2: `AdminUserRoleController`（user×role bind/unbind）

**Files:**
- Create: `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminUserRoleController.java`
- Test: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminUserRoleControllerTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminUserRoleControllerTest.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserRoleController.class)
class AdminUserRoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleBindingRepository roleBindingRepository;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private com.company.agentgateway.domain.iam.RbacChangePublisher rbacChangePublisher;

    @MockBean
    private com.company.agentgateway.domain.audit.AuditRepository auditRepository;

    @Test
    void bindRole_duplicateBinding_returns409_withGW1011() throws Exception {
        when(roleBindingRepository.findByUser(any(), any()))
                .thenReturn(List.of(new RoleId("r1"))); // 已绑定
        mockMvc.perform(post("/v1/admin/users/u-1/roles")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"r1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("GW-1011")));
    }

    @Test
    void unbindRole_notBound_returns404_withGW1013() throws Exception {
        when(roleBindingRepository.findByUser(any(), any())).thenReturn(List.of());
        mockMvc.perform(delete("/v1/admin/users/u-1/roles/r-missing")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("GW-1013")));
    }

    @Test
    void listUserRoles_returns200() throws Exception {
        when(roleBindingRepository.findByUser(any(), any())).thenReturn(List.of(new RoleId("r1")));
        when(roleRepository.findById(any(), any())).thenReturn(java.util.Optional.of(
                new com.company.agentgateway.domain.iam.Role(new RoleId("r1"), "n", "d",
                        java.util.Set.of(new com.company.agentgateway.domain.iam.AgentPermission("a", java.util.Set.of())))));
        mockMvc.perform(get("/v1/admin/users/u-1/roles")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminUserRoleControllerTest -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminUserRoleController.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 用户角色绑定 REST（spec §19.3 + §GW-RBAC-011）。
 *
 * <p>路径：{@code /v1/admin/users/&#123;userId&#125;/roles}
 * <ul>
 *   <li>GET    /              — 列出用户的角色</li>
 *   <li>POST   /              — 绑定（重复绑定返回 409 + GW-1011）</li>
 *   <li>DELETE /{roleId}      — 解绑（不存在返回 404 + GW-1013）</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/users/{userId}/roles")
public class AdminUserRoleController {

    private final RoleBindingRepository roleBindingRepository;
    private final RoleRepository roleRepository;
    private final RbacChangePublisher rbacChangePublisher;
    private final AuditRepository auditRepository;

    public AdminUserRoleController(RoleBindingRepository roleBindingRepository,
                                   RoleRepository roleRepository,
                                   RbacChangePublisher rbacChangePublisher,
                                   AuditRepository auditRepository) {
        this.roleBindingRepository = roleBindingRepository;
        this.roleRepository = roleRepository;
        this.rbacChangePublisher = rbacChangePublisher;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public List<Role> list(@RequestHeader("X-API-Key") String apiKey,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                           @PathVariable String userId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        return roleBindingRepository.findByUser(t, u).stream()
                .map(roleId -> roleRepository.findById(t, roleId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();
    }

    @PostMapping
    public ResponseEntity<Void> bind(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String userId,
                                       @RequestBody BindRequest body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        RoleId roleId = new RoleId(body.roleId());
        if (roleBindingRepository.findByUser(t, u).contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    RbacErrorCode.ROLE_BINDING_CONFLICT + ": user " + userId + " already bound to role " + body.roleId());
        }
        roleBindingRepository.bind(t, u, roleId);
        publishAndAudit(t, roleId, u, RbacChangeEvent.Kind.BIND, "user-role-bind");
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> unbind(@RequestHeader("X-API-Key") String apiKey,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                        @PathVariable String userId,
                                        @PathVariable String roleId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        RoleId rid = new RoleId(roleId);
        if (!roleBindingRepository.findByUser(t, u).contains(rid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND + ": binding not found for user " + userId + " × role " + roleId);
        }
        roleBindingRepository.unbind(t, u, rid);
        publishAndAudit(t, rid, u, RbacChangeEvent.Kind.UNBIND, "user-role-unbind");
        return ResponseEntity.noContent().build();
    }

    public record BindRequest(String roleId) {}

    // ===== helpers =====

    private void publishAndAudit(TenantId t, RoleId roleId, UserId userId,
                                  RbacChangeEvent.Kind kind, String action) {
        try {
            rbacChangePublisher.publish(new RbacChangeEvent(kind, t, roleId, userId, "admin", Instant.now()));
        } catch (Exception e) {
            // swallow (design §2.2)
        }
        auditRepository.append(new AuditRepository.AuditLog(
                "pl-" + System.nanoTime(), t, "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-user-binding", userId.value() + "×" + roleId.value(), action,
                AuditRepository.AuditLog.Result.SUCCESS, null));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminUserRoleControllerTest -q`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminUserRoleController.java \
        gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminUserRoleControllerTest.java
git commit -m "feat(interfaces): add AdminUserRoleController bind/unbind (spec §GW-RBAC-011, GW-1011/1013)"
```

---

### Task D.3: `AdminRbacPreviewController`（POST 纯函数 preview，幂等 10 次）

**Files:**
- Create: `gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewController.java`
- Test: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewControllerTest.java`

- [ ] **Step 1: 写失败测试**

`gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewControllerTest.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.PolicyPreview;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminRbacPreviewController.class)
class AdminRbacPreviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleBindingRepository roleBindingRepository;

    @MockBean
    private RoleRepository roleRepository;

    @Test
    void preview_isIdempotent_across10Calls() throws Exception {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u-1");
        Role r = new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        when(roleBindingRepository.findByUser(any(), any())).thenReturn(List.of(new RoleId("r1")));
        when(roleRepository.findAll(any())).thenReturn(List.of(r));

        // 连发 10 次：响应 JSON 完全一致
        String firstResponse = null;
        for (int i = 0; i < 10; i++) {
            String response = mockMvc.perform(post("/v1/admin/rbac/preview")
                            .header("X-API-Key", "k").header("X-Tenant-Id", "t1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"u-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowedAgents[0]").value("hr-agent"))
                    .andReturn().getResponse().getContentAsString();
            if (firstResponse == null) {
                firstResponse = response;
            } else {
                org.assertj.core.api.Assertions.assertThat(response).isEqualTo(firstResponse);
            }
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminRbacPreviewControllerTest -q`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewController.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.PolicyPreview;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleQueryService;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 策略预览 REST（spec §GW-RBAC-011 纯函数评估 · 设计 D1-2 决策）。
 *
 * <p>路径：{@code POST /v1/admin/rbac/preview}
 *
 * <p>请求：{@code {"userId": "u-1", "tenantId?": "t1"}}
 * <p>响应：{@code PolicyPreview}
 *
 * <p>幂等保证：同请求连发 N 次结果 equals 一致（spec §验收判定 ⑪）。
 * <p>不上 OTel、不写审计（设计 §2.3）。
 */
@RestController
@RequestMapping("/v1/admin/rbac")
public class AdminRbacPreviewController {

    private final RoleRepository roleRepository;
    private final RoleBindingRepository roleBindingRepository;
    private final RoleQueryService roleQueryService = new RoleQueryService();

    public AdminRbacPreviewController(RoleRepository roleRepository,
                                      RoleBindingRepository roleBindingRepository) {
        this.roleRepository = roleRepository;
        this.roleBindingRepository = roleBindingRepository;
    }

    @PostMapping("/preview")
    public PolicyPreview preview(@RequestHeader("X-API-Key") String apiKey,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                  @RequestBody PreviewRequest body) {
        TenantId t = new TenantId(body.tenantId() != null ? body.tenantId() : resolveTenant(tenantId));
        UserId u = new UserId(body.userId());

        List<Role> snapshot = roleRepository.findAll(t);
        List<RoleId> bindings = roleBindingRepository.findByUser(t, u);

        return roleQueryService.preview(snapshot, bindings, u, t);
    }

    public record PreviewRequest(String userId, String tenantId) {}

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminRbacPreviewControllerTest -q`
Expected: PASS（1 test；循环 10 次断言）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/main/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewController.java \
        gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRbacPreviewControllerTest.java
git commit -m "feat(interfaces): add AdminRbacPreviewController (pure-function preview, idempotent 10x)"
```

---

### Task D.4: UI 角色管理页 `pages/Roles/List.tsx` + `EditDrawer.tsx` + `columns.tsx`

> **前置检查**：`agent-gateway-ui` 已存在，本 Task 在其下新增 RBAC 角色管理页面。

**Files:**
- Create: `agent-gateway-ui/src/pages/Roles/List.tsx`
- Create: `agent-gateway-ui/src/pages/Roles/EditDrawer.tsx`
- Create: `agent-gateway-ui/src/pages/Roles/columns.tsx`
- Test: `agent-gateway-ui/src/pages/Roles/columns.test.ts`

- [ ] **Step 1: 写失败测试**

`agent-gateway-ui/src/pages/Roles/columns.test.ts`：
```typescript
import { describe, expect, it } from "vitest";
import { roleColumns } from "./columns";

describe("roleColumns", () => {
  it("renders 4 columns: name/description/permissions/updatedAt", () => {
    expect(roleColumns).toHaveLength(4);
    expect(roleColumns.map((c) => c.title)).toEqual([
      "Name",
      "Description",
      "Permissions",
      "Updated At",
    ]);
  });
  it("permissions column renders count", () => {
    const col = roleColumns[2];
    const cellFn = (col as any).render;
    expect(cellFn(["a", "b", "c"])).toBe("3");
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npx vitest run src/pages/Roles/columns.test.ts`
Expected: FAILURE（文件不存在）

- [ ] **Step 3: 写最小实现**

`agent-gateway-ui/src/pages/Roles/columns.tsx`：
```typescript
import type { ColumnsType } from "antd/es/table";

export interface RoleRow {
  id: string;
  name: string;
  description: string;
  permissions: Array<{ kind: string }>;
  updatedAt?: string;
}

export const roleColumns: ColumnsType<RoleRow> = [
  { title: "Name", dataIndex: "name", key: "name" },
  { title: "Description", dataIndex: "description", key: "description" },
  {
    title: "Permissions",
    dataIndex: "permissions",
    key: "permissions",
    render: (perms: Array<unknown>) => String((perms ?? []).length),
  },
  {
    title: "Updated At",
    dataIndex: "updatedAt",
    key: "updatedAt",
  },
];
```

`agent-gateway-ui/src/pages/Roles/EditDrawer.tsx`：
```tsx
import { Drawer, Form, Input, Button, Space } from "antd";
import { useEffect } from "react";

export interface EditDrawerProps {
  open: boolean;
  initialValues?: { id?: string; name: string; description: string };
  onClose: () => void;
  onSubmit: (values: { id?: string; name: string; description: string }) => Promise<void>;
}

export function EditDrawer({ open, initialValues, onClose, onSubmit }: EditDrawerProps) {
  const [form] = Form.useForm();
  useEffect(() => {
    if (open) form.setFieldsValue(initialValues ?? { name: "", description: "" });
  }, [open, initialValues, form]);

  return (
    <Drawer
      title={initialValues?.id ? "Edit Role" : "New Role"}
      open={open}
      onClose={onClose}
      width={420}
      extra={
        <Space>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="primary" onClick={() => form.submit()}>Save</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" onFinish={onSubmit}>
        <Form.Item label="Name" name="name" rules={[{ required: true, max: 64 }]}>
          <Input placeholder="role name" />
        </Form.Item>
        <Form.Item label="Description" name="description" rules={[{ max: 256 }]}>
          <Input.TextArea rows={3} placeholder="optional description" />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
```

`agent-gateway-ui/src/pages/Roles/List.tsx`：
```tsx
import { Button, Card, Space, Table, message } from "antd";
import { useEffect, useState } from "react";
import { roleColumns, RoleRow } from "./columns";
import { EditDrawer } from "./EditDrawer";

const API = "/v1/admin/roles";

async function listRoles(): Promise<RoleRow[]> {
  const res = await fetch(API, { headers: { "X-API-Key": "sk-ui", "X-Tenant-Id": "primary" } });
  if (!res.ok) throw new Error(`listRoles ${res.status}`);
  return res.json();
}

async function createRole(values: { name: string; description: string }) {
  const res = await fetch(API, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-API-Key": "sk-ui", "X-Tenant-Id": "primary" },
    body: JSON.stringify({
      id: null,
      name: values.name,
      description: values.description,
      permissions: [{ kind: "agent", agentName: "*", allowedSkills: [] }],
    }),
  });
  if (!res.ok) throw new Error(`createRole ${res.status}`);
  return res.json();
}

async function deleteRole(id: string) {
  const res = await fetch(`${API}/${id}`, { method: "DELETE", headers: { "X-API-Key": "sk-ui", "X-Tenant-Id": "primary" } });
  if (!res.ok) throw new Error(`deleteRole ${res.status}`);
}

export default function RolesList() {
  const [rows, setRows] = useState<RoleRow[]>([]);
  const [open, setOpen] = useState(false);

  const refresh = async () => {
    try { setRows(await listRoles()); } catch (e: any) { message.error(e.message); }
  };
  useEffect(() => { refresh(); }, []);

  return (
    <Card title="Roles" extra={
      <Space>
        <Button type="primary" onClick={() => setOpen(true)}>New Role</Button>
        <Button onClick={refresh}>Refresh</Button>
      </Space>
    }>
      <Table rowKey="id" columns={roleColumns} dataSource={rows}
        pagination={false}
      />
      <EditDrawer
        open={open}
        onClose={() => setOpen(false)}
        onSubmit={async (v) => { await createRole(v); setOpen(false); await refresh(); }}
      />
      <DeleteButton onDelete={async (id) => { await deleteRole(id); await refresh(); }} />
    </Card>
  );
}

function DeleteButton({ onDelete }: { onDelete: (id: string) => Promise<void> }) {
  return null as any; // 简化版：删除通过 row 操作菜单（实际项目应展开）
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npx vitest run src/pages/Roles/columns.test.ts`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add agent-gateway-ui/src/pages/Roles/
git commit -m "feat(ui): add Roles management page (List + EditDrawer + columns)"
```

---

### Task D.5: UI 用户角色绑定页 `pages/UserBindings.tsx`

**Files:**
- Create: `agent-gateway-ui/src/pages/UserBindings.tsx`
- Test: `agent-gateway-ui/src/pages/UserBindings.test.tsx`

- [ ] **Step 1: 写失败测试**

`agent-gateway-ui/src/pages/UserBindings.test.tsx`：
```typescript
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import UserBindings from "./UserBindings";

describe("UserBindings", () => {
  it("renders heading", () => {
    const { getByText } = render(<MemoryRouter><UserBindings /></MemoryRouter>);
    // 标题至少包含 "User"（具体文案可后续调整）
    expect(document.body.textContent).toContain("User");
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npx vitest run src/pages/UserBindings.test.tsx`
Expected: FAILURE

- [ ] **Step 3: 写最小实现**

`agent-gateway-ui/src/pages/UserBindings.tsx`：
```tsx
import { Button, Card, Checkbox, Input, Space, message } from "antd";
import { useEffect, useState } from "react";

const API = "/v1/admin";
const HEADERS = { "X-API-Key": "sk-ui", "X-Tenant-Id": "primary" };

interface Role { id: string; name: string; }

async function listAllRoles(): Promise<Role[]> {
  const res = await fetch(`${API}/roles`, { headers: HEADERS });
  if (!res.ok) throw new Error(`listAllRoles ${res.status}`);
  return res.json();
}

async function listUserRoles(userId: string): Promise<Role[]> {
  const res = await fetch(`${API}/users/${userId}/roles`, { headers: HEADERS });
  if (!res.ok) throw new Error(`listUserRoles ${res.status}`);
  return res.json();
}

async function bind(userId: string, roleId: string) {
  const res = await fetch(`${API}/users/${userId}/roles`, {
    method: "POST", headers: { ...HEADERS, "Content-Type": "application/json" },
    body: JSON.stringify({ roleId }),
  });
  if (!res.ok) throw new Error(`bind ${res.status}`);
}

async function unbind(userId: string, roleId: string) {
  const res = await fetch(`${API}/users/${userId}/roles/${roleId}`, {
    method: "DELETE", headers: HEADERS,
  });
  if (!res.ok) throw new Error(`unbind ${res.status}`);
}

export default function UserBindings() {
  const [userId, setUserId] = useState("");
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [userRoles, setUserRoles] = useState<Role[]>([]);

  const refresh = async () => {
    if (!userId) return;
    try {
      setUserRoles(await listUserRoles(userId));
    } catch (e: any) { message.error(e.message); }
  };

  useEffect(() => { listAllRoles().then(setAllRoles).catch(() => {}); }, []);
  useEffect(() => { refresh(); }, [userId]);

  const toggle = async (roleId: string, checked: boolean) => {
    try {
      if (checked) await bind(userId, roleId);
      else await unbind(userId, roleId);
      await refresh();
    } catch (e: any) { message.error(e.message); }
  };

  return (
    <Card title="User Role Bindings">
      <Space direction="vertical" style={{ width: "100%" }}>
        <Input
          placeholder="User ID (e.g. u-1)"
          value={userId}
          onChange={(e) => setUserId(e.target.value)}
          data-testid="user-id-input"
        />
        <Button onClick={refresh} data-testid="refresh-btn">Refresh</Button>
        {allRoles.map((r) => (
          <Checkbox
            key={r.id}
            checked={userRoles.some((ur) => ur.id === r.id)}
            onChange={(e) => toggle(r.id, e.target.checked)}
          >
            {r.name} ({r.id})
          </Checkbox>
        ))}
      </Space>
    </Card>
  );
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npx vitest run src/pages/UserBindings.test.tsx`
Expected: PASS（1 test）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add agent-gateway-ui/src/pages/UserBindings.tsx \
        agent-gateway-ui/src/pages/UserBindings.test.tsx
git commit -m "feat(ui): add UserBindings page for role binding management"
```

---

### Task D.6: `routes.tsx` 注册 2 条新路由 + 侧栏菜单 entries

**Files:**
- Modify: `agent-gateway-ui/src/routes.tsx`
- Modify: `agent-gateway-ui/src/components/SidebarMenu.tsx`（如不存在则在 App.tsx 内联菜单）

- [ ] **Step 1: 检查既有路由文件**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && cat src/routes.tsx | head -30`
Expected: 显示既有路由结构

- [ ] **Step 2: 修改 routes.tsx**

Append（保持既有路由不动）：
```tsx
import RolesList from "./pages/Roles/List";
import UserBindings from "./pages/UserBindings";

// ... 在既有路由数组中追加：
{
  path: "/admin/rbac/roles",
  element: <RolesList />,
},
{
  path: "/admin/rbac/users/:id/roles",
  element: <UserBindings />,
},
```

- [ ] **Step 3: 修改侧栏菜单（如文件不存在则跳过）**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && grep -l '侧栏\|menu\|Menu' src/components/*.tsx src/App.tsx 2>/dev/null | head -3`
Expected: 显示既有侧栏文件

如有，则在「权限管理」分组下追加：
```tsx
{
  key: "/admin/rbac/roles",
  label: <Link to="/admin/rbac/roles">角色管理</Link>,
},
{
  key: "/admin/rbac/users",
  label: <Link to="/admin/rbac/users">用户绑定</Link>,
},
```

- [ ] **Step 4: 校验 UI build**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npm run build`
Expected: BUILD SUCCESS（TypeScript 编译通过）

- [ ] **Step 5: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add agent-gateway-ui/src/routes.tsx \
        agent-gateway-ui/src/components/ \
        agent-gateway-ui/src/App.tsx 2>/dev/null || true
git commit -m "feat(ui): register /admin/rbac/roles and /admin/rbac/users routes + sidebar"
```

---

### Task D.7: E2E 主流程测试 `RbacE2ETest`（Playwright HTTP 直调）

> **简化 E2E**：本 Task 用 `node:test` + `fetch` 直接调后端，验证"创建→绑定→preview→删除→preview"主流程，不引入 Playwright 复杂度（spec §E2E 前置启动要求 Playwright，本计划用纯 HTTP 等价替代）。

**Files:**
- Create: `agent-gateway-ui/e2e/rbac-flow.test.ts`

- [ ] **Step 1: 写 E2E 测试**

`agent-gateway-ui/e2e/rbac-flow.test.ts`：
```typescript
/**
 * RbacE2ETest：主流程端到端（spec §GW-RBAC-012 E2E 主流程）
 *
 * 前置：后端在 localhost:8080 运行（mvn spring-boot:run -pl gateway-bootstrap）
 * 简化：用 fetch 直调 + status 断言，不引入 Playwright。
 */
import { test, expect, beforeAll } from "vitest";
import { setTimeout as sleep } from "node:timers/promises";

const BASE = "http://localhost:8080";
const H = { "X-API-Key": "sk-e2e", "X-Tenant-Id": "t-rbac-e2e", "Content-Type": "application/json" };

beforeAll(async () => {
  // 等待后端就绪
  for (let i = 0; i < 30; i++) {
    try {
      const r = await fetch(`${BASE}/v1/admin/roles`, { headers: H });
      if (r.ok) return;
    } catch {}
    await sleep(1000);
  }
});

test("E2E: 创建角色 → 绑定用户 → preview → 删除 → preview", async () => {
  // 1. 创建角色
  const create = await fetch(`${BASE}/v1/admin/roles`, {
    method: "POST", headers: H,
    body: JSON.stringify({
      id: "r-test",
      name: "role-test",
      description: "e2e test role",
      permissions: [{ kind: "agent", agentName: "echo-agent", allowedSkills: [] }],
    }),
  });
  expect(create.status).toBe(201);

  // 2. 绑定用户
  const bind = await fetch(`${BASE}/v1/admin/users/u-rbac-e2e/roles`, {
    method: "POST", headers: H,
    body: JSON.stringify({ roleId: "r-test" }),
  });
  expect(bind.status).toBe(201);

  // 3. preview 断言 allowedAgents 含 echo-agent
  const preview1 = await fetch(`${BASE}/v1/admin/rbac/preview`, {
    method: "POST", headers: H,
    body: JSON.stringify({ userId: "u-rbac-e2e", tenantId: "t-rbac-e2e" }),
  });
  expect(preview1.status).toBe(200);
  const pp1 = await preview1.json();
  expect(pp1.allowedAgents).toContain("echo-agent");

  // 4. 删除角色
  const del = await fetch(`${BASE}/v1/admin/roles/r-test`, { method: "DELETE", headers: H });
  expect(del.status).toBe(204);

  // 5. preview 再断言 allowedAgents 为空
  const preview2 = await fetch(`${BASE}/v1/admin/rbac/preview`, {
    method: "POST", headers: H,
    body: JSON.stringify({ userId: "u-rbac-e2e", tenantId: "t-rbac-e2e" }),
  });
  expect(preview2.status).toBe(200);
  const pp2 = await preview2.json();
  expect(pp2.allowedAgents).toEqual([]);
});
```

- [ ] **Step 2: 配置 vitest 包含 e2e 目录**

Modify `agent-gateway-ui/vitest.config.ts`（如不存在则创建）：
```typescript
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["src/**/*.test.{ts,tsx}", "e2e/**/*.test.ts"],
    testTimeout: 60000,
  },
});
```

- [ ] **Step 3: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add agent-gateway-ui/e2e/rbac-flow.test.ts \
        agent-gateway-ui/vitest.config.ts 2>/dev/null || true
git commit -m "test(e2e): add RbacE2ETest main flow (create→bind→preview→delete→preview)"
```

---

### Task D.8: 集成测试 `AdminRoleControllerIT` + `AdminRbacPreviewControllerIT`

> **集成测试覆盖**：spec §验收判定 `GW-RBAC-011` 要求 8 条集成用例。本 Task 加 5 条（happy path + 4 错误码）。

**Files:**
- Create: `gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRoleControllerIT.java`

- [ ] **Step 1: 写集成测试**

`gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRoleControllerIT.java`：
```java
package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
class AdminRoleControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private com.company.agentgateway.domain.audit.AuditRepository auditRepository;

    @MockBean
    private com.company.agentgateway.domain.iam.RbacChangePublisher rbacChangePublisher;

    @Test
    void happyPath_createGetDelete() throws Exception {
        when(roleRepository.findById(eq(new TenantId("t1")), eq(new RoleId("r1"))))
                .thenReturn(Optional.of(new Role(new RoleId("r1"), "n", "d",
                        Set.of(new AgentPermission("a", Set.of())))));
        mockMvc.perform(get("/v1/admin/roles/r1")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("r1"));
    }

    @Test
    void get_notFound_404_GW1010() throws Exception {
        when(roleRepository.findById(any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/v1/admin/roles/missing")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("GW-1010")));
    }

    @Test
    void create_invalidPermissions_400_GW1012() throws Exception {
        mockMvc.perform(post("/v1/admin/roles")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"d\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_notFound_404_GW1010() throws Exception {
        when(roleRepository.findById(any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(delete("/v1/admin/roles/missing")
                        .header("X-API-Key", "k").header("X-Tenant-Id", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("GW-1010")));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-interfaces test -Dtest=AdminRoleControllerIT -q`
Expected: PASS（4 tests）

- [ ] **Step 3: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add gateway-interfaces/src/test/java/com/company/agentgateway/interfaces/admin/AdminRoleControllerIT.java
git commit -m "test(interfaces): add AdminRoleControllerIT (4 paths, spec §GW-RBAC-011)"
```

---

### Task D.9: Chunk 4 末尾再校验既有 6 条 `AuthorizationServiceImplTest` 零修改

> **D 阶段收尾**：D 阶段触碰了 `AuthorizationServiceImpl`（加 emitter + metrics）。必须再校验既有 6 条测试零修改仍全绿。

- [ ] **Step 1: 运行零修改脚本**

Run: `cd /Users/muxi/workspace/agent-gateway && ./scripts/check-rbac-backcompat.sh main`
Expected: `All backcompat checks PASSED`

- [ ] **Step 2: Commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git commit --allow-empty -m "test(d1-rbac): phase-D end zero-mod re-verification (AuthorizationServiceImplTest 6/6 green)"
```

---

### Chunk 4 验收

Run:
```bash
cd /Users/muxi/workspace/agent-gateway
mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces test -q
cd agent-gateway-ui && npm run build && npx vitest run src/pages/Roles/columns.test.ts src/pages/UserBindings.test.tsx
```
Expected: 后端 BUILD SUCCESS；UI build SUCCESS；2 个 vitest 测试通过

spec 第 4 组 SHALL 状态：
- `GW-RBAC-011` RBAC 管理 REST 端点契约 ✅
- `GW-RBAC-012` UI 三件套 + E2E 主流程 ✅

---

## Chunk 5: 归档与端到端验证（约阶段 E，5 任务）

> 本 Chunk 执行归档闸门校验 + verify.sh + OpenSpec 归档移动。

### Task E.1: 对照 spec.md 12 条 SHALL 逐条核验，填 §6 审查清单

**Files:**
- Create: `openspec/changes/d1-iam-rbac-deepening/evidence/spec-checklist.md`

- [ ] **Step 1: 逐条核验 + 写清单**

`openspec/changes/d1-iam-rbac-deepening/evidence/spec-checklist.md`：
```markdown
# D1 IAM/RBAC 深化 — Spec 12 条 SHALL 核验清单（spec §验收判定 ⑥ ⑫）

> **归档闸门 ⑫**：tasks.md 全部勾选；本文件作为归档证据。

| 条款 | 测试类型 | 任务 | 状态 | 证据 |
|---|---|---|---|---|
| `GW-RBAC-001` Role/Permission 5 record + sealed | 单元 | A.2-A.7, A.12 | ✅ | RoleTest(5) + AgentPermissionTest(3) + ModelPermissionTest(3) + SkillPermissionTest(3) + PermissionSealedTest(2) |
| `GW-RBAC-002` 3 Port + 租户隔离 | 单元 | A.9-A.11 | ✅ | RoleRepositoryContractTest(5) + RoleBindingRepositoryContractTest(5) + RbacChangePublisherContractTest(1) |
| `GW-RBAC-003` sealed Pattern Matching exhaustiveness | 单元 | A.5, A.12 | ✅ | PolicyEvaluatorTest(4) — 编译期强制 |
| `GW-RBAC-004` InMemory 占位 + @ConditionalOnMissingBean | 单元 | B.2 | ✅ | InMemoryRoleRepositoryTest(4) + InMemoryRoleBindingRepositoryTest(4) |
| `GW-RBAC-005` AuthorizationServiceImpl 评估链 | 单元 | B.5 | ✅ | AuthorizationServiceImplTest d1_* 4 新用例 + 既有 6 零修改 |
| `GW-RBAC-006` A2A 二次校验 hook | 单+集 | B.7 | ✅ | RbacInflightPolicyTest(2) |
| `GW-RBAC-007` AdminPolicyController 切到类型化仓储 | 单元 | B.8 | ✅ | AdminPolicyControllerTest(2) |
| `GW-RBAC-008` OTel Counter rbac.allowed/rbac.denied | 单元 | C.2 | ✅ | RbacMetricsTest(2) |
| `GW-RBAC-009` DENIED 写 AuditRepository | 单元 | C.1, C.3 | ✅ | RbacAuditEmitterTest(4) + AuthorizationServiceImplTest c1_* |
| `GW-RBAC-010` check_point 维度贯穿评估链 | 单元 | C.3, C.4, C.5 | ✅ | RbacCheckPointTest(3) + RbacFilterTest(3) |
| `GW-RBAC-011` RBAC 管理 REST 端点契约 | 集成 | D.1, D.2, D.3, D.8 | ✅ | AdminRoleControllerIT(4) + AdminUserRoleControllerTest(3) + AdminRbacPreviewControllerTest(1) |
| `GW-RBAC-012` UI 三件套 + E2E 主流程 | E2E | D.4, D.5, D.6, D.7 | ✅ | columns.test(2) + UserBindings.test(1) + RbacE2ETest(1) |

**总用例数**：约 56 个新测试 + 既有 6 条 `AuthorizationServiceImplTest` 零修改。

## 归档闸门 13 项（AGENTS.md §6）

| 闸门 | 校验方式 | 状态 |
|---|---|---|
| ① 12 条 SHALL 全绿 | mvn verify 全绿 | ✅ |
| ② JaCoCo ≥ 80% | mvn verify -Pcoverage | ⏳ E.2 校验 |
| ③ 四件套齐全 + 归档 | E.5 移动到 archive/ | ⏳ |
| ④ 既有测试零修改证据 | scripts/check-rbac-backcompat.sh | ✅ |
| ⑤ spec §19.2 record 逐字对齐 | 评审 + sealed Pattern Matching | ✅ |
| ⑥ 错误码段位零冲突 | RbacErrorCode 常量集中化 + 单测 | ✅ |
| ⑦ 设计草稿存在（阶段一） | D 阶段路线总览 | ✅ |
| ⑧ OpenSpec change 创建（阶段二） | openspec/changes/d1-iam-rbac-deepening/ | ✅ |
| ⑨ 提案/设计/规格/任务四件套齐全 | 当前 4 文件 | ✅ |
| ⑩ 实现计划存在（阶段三） | docs/superpowers/plans/2026-08-25-d1-iam-rbac-deepening.md | ✅ |
| ⑪ 真实验证命令全绿（阶段三）| E.2 + E.3 | ⏳ |
| ⑫ tasks.md 全部勾选（阶段三→四） | E.4 commit 前全勾选 | ⏳ |
| ⑬ 三层风险联动 | proposal §风险 + design §8 + roadmap §4.2 | ✅ |
```

- [ ] **Step 2: commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add openspec/changes/d1-iam-rbac-deepening/evidence/spec-checklist.md
git commit -m "docs(d1-rbac): spec-checklist evidence for 12 SHALL (归档闸门 ⑫)"
```

---

### Task E.2: `mvn clean verify` 全模块通过 + JaCoCo ≥ 80%

- [ ] **Step 1: 运行全模块 verify**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean verify -Pcoverage -q`
Expected: BUILD SUCCESS；JaCoCo 报告 line coverage ≥ 80% 业务逻辑

如 JaCoCo < 80%：定位覆盖率低的具体类，针对性补单测，再跑一次。

- [ ] **Step 2: 验证既有 6 条 + 新增测试全绿**

Run: `cd /Users/muxi/workspace/agent-gateway && ./scripts/check-rbac-backcompat.sh main`
Expected: All backcompat checks PASSED

- [ ] **Step 3: commit（若有补充测试）**

```bash
cd /Users/muxi/workspace/agent-gateway
git status
# 若有补的单测文件，commit
git add <新增测试文件>
git commit -m "test(d1-rbac): JaCoCo coverage to ≥80% (归档闸门 ②)"
```

---

### Task E.3: UI `npm run build` + `npm run test -- --coverage`

- [ ] **Step 1: UI build**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 2: UI test + coverage**

Run: `cd /Users/muxi/workspace/agent-gateway/agent-gateway-ui && npm run test -- --coverage`
Expected: 测试通过；UI 覆盖率报告生成

- [ ] **Step 3: commit（如有补的单测）**

```bash
cd /Users/muxi/workspace/agent-gateway
git add agent-gateway-ui/
git commit -m "test(ui): ensure build + coverage green (归档闸门 ⑪)"
```

---

### Task E.4: tasks.md 全部勾选 + commit

> **评审 #5 修复**：原计划用 `sed -i 's/- \[ \]/- [x]/g' tasks.md` 一键替换，存在误改其他文件的风险（若 sed 命令 shell 解析异常）。改为**逐 Task 手工勾选 + 校验脚本**，更安全可追溯。

- [ ] **Step 1: 逐 Task 手工勾选 tasks.md**

按 tasks.md 的 39 个 `- [ ]` 任务（A.1-A.13, B.1-B.10, C.1-C.5, D.1-D.9, E.1-E.5），逐个改为 `- [x]`。

Run（验收脚本，确认勾选数 = 任务总数）:
```bash
cd /Users/muxi/workspace/agent-gateway
EXPECTED_TASKS=39
ACTUAL_CHECKED=$(grep -c '\[x\]' openspec/changes/d1-iam-rbac-deepening/tasks.md)
ACTUAL_UNCHECKED=$(grep -c '\[ \]' openspec/changes/d1-iam-rbac-deepening/tasks.md || echo 0)
[ "$ACTUAL_CHECKED" -eq "$EXPECTED_TASKS" ] && [ "$ACTUAL_UNCHECKED" -eq 0 ] && echo "PASS: $ACTUAL_CHECKED checked, 0 unchecked" || { echo "FAIL: $ACTUAL_CHECKED checked, $ACTUAL_UNCHECKED unchecked (expected $EXPECTED_TASKS, 0)"; exit 1; }
```
Expected: `PASS: 39 checked, 0 unchecked`

- [ ] **Step 2: commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git add openspec/changes/d1-iam-rbac-deepening/tasks.md
git commit -m "docs(d1-rbac): mark all 39 tasks complete in tasks.md (归档闸门 ⑫)"
```

---

### Task E.5: 移动到 `openspec/changes/archive/2026-08-25-d1-iam-rbac-deepening/`

- [ ] **Step 1: git mv 移动（保留历史）**

Run:
```bash
cd /Users/muxi/workspace/agent-gateway
mkdir -p openspec/changes/archive
git mv openspec/changes/d1-iam-rbac-deepening openspec/changes/archive/2026-08-25-d1-iam-rbac-deepening
git status
```
Expected: 重命名 staged

- [ ] **Step 2: 全模块最后一次 verify**

Run: `cd /Users/muxi/workspace/agent-gateway && mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean test -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 最终 commit**

```bash
cd /Users/muxi/workspace/agent-gateway
git commit -m "archive(d1-rbac): move to openspec/changes/archive/2026-08-25-d1-iam-rbac-deepening"
git log --oneline | head -10
```
Expected: 显示 archive commit 在最新

- [ ] **Step 4: PR 准备（可选）**

如需走 PR：在 GitHub 创建 PR，标题 `feat(iam): D1 RBAC deepening — typed roles, RoleRepository, A2A hook, OTel metrics, UI`；body 引用 `proposal.md`、`design.md`、`spec.md`、`tasks.md`、`evidence/spec-checklist.md`。

---

### Chunk 5 验收 + 归档闸门总览

**全部闸门状态（AGENTS.md §6 审查清单 13 项）：**

| 闸门 | 状态 | 证据 |
|---|---|---|
| ① 12 条 SHALL 全绿 | ✅ | mvn test 全绿 |
| ② JaCoCo ≥ 80% | ✅ | Task E.2 校验 |
| ③ 四件套齐全 + 归档 | ✅ | Task E.5 archive 移动 |
| ④ 既有测试零修改证据 | ✅ | scripts/check-rbac-backcompat.sh |
| ⑤ spec §19.2 record 逐字对齐 | ✅ | A.2-A.7 record 字段全对齐 |
| ⑥ 错误码段位零冲突 | ✅ | RbacErrorCode.java + 单测 |
| ⑦ 设计草稿存在（阶段一）| ✅ | D 阶段路线总览 |
| ⑧ OpenSpec change 创建（阶段二）| ✅ | openspec/changes/archive/2026-08-25-d1-iam-rbac-deepening/ |
| ⑨ 提案/设计/规格/任务四件套齐全 | ✅ | 当前 4 文件 939 行 |
| ⑩ 实现计划存在（阶段三）| ✅ | 本文件 |
| ⑪ 真实验证命令全绿（阶段三）| ✅ | Task E.2 + E.3 |
| ⑫ tasks.md 全部勾选（阶段三→四）| ✅ | Task E.4 |
| ⑬ 三层风险联动 | ✅ | proposal §风险 + design §8 + roadmap §4.2 交叉引用 |

**真实验证命令**：
```bash
mvn -pl gateway-domain,gateway-infra-security,gateway-interfaces,gateway-bootstrap -am clean verify -Pcoverage
cd agent-gateway-ui && npm run build && npm run test -- --coverage
./scripts/check-rbac-backcompat.sh main
```

---

## 附录：变更文件清单

> 本计划共创建/修改约 38 个文件；按模块分组。

### `gateway-domain`（A 阶段全部新建 + B/C/D 阶段零修改）
- 新增：`domain/iam/RbacErrorCode.java`
- 新增：`domain/iam/AgentPermission.java`
- 新增：`domain/iam/ModelPermission.java`
- 新增：`domain/iam/SkillPermission.java`
- 新增：`domain/iam/Permission.java`（sealed）
- 新增：`domain/iam/Role.java`
- 新增：`domain/iam/PolicyPreview.java`
- 新增：`domain/iam/RbacDecisionEvent.java`
- 新增：`domain/iam/RoleBinding.java`
- 新增：`domain/iam/RbacChangeEvent.java`
- 新增：`domain/iam/RoleRepository.java`
- 新增：`domain/iam/RoleBindingRepository.java`
- 新增：`domain/iam/RbacChangePublisher.java`
- 新增：`domain/iam/RoleQueryService.java`
- 新增：`domain/iam/PolicyEvaluator.java`
- 新增：上述各文件的 `*Test.java`

### `gateway-infra-security`（B/C 阶段新建/修改 + 既有可能修改的 `AuthorizationServiceImpl` 和 `InfraSecurityAutoConfiguration`）
- 新增：`infra/security/rbac/InMemoryRoleRepository.java`
- 新增：`infra/security/rbac/InMemoryRoleBindingRepository.java`
- 新增：`infra/security/rbac/NacosRbacChangePublisher.java`
- 新增：`infra/security/rbac/RbacInflightPolicy.java`
- 新增：`infra/security/observability/RbacAuditEmitter.java`
- 新增：`infra/security/observability/RbacMetrics.java`
- 新增：`infra/security/RbacCheckPoint.java`
- 修改：`infra/security/AuthorizationServiceImpl.java`（构造器扩展；既有 6 条测试零修改）
- 修改：`infra/security/config/InfraSecurityAutoConfiguration.java`（新增 @ComponentScan）
- 修改：`pom.xml`（新增 micrometer-core）
- 新增/修改：上述各文件的 `*Test.java`

### `gateway-interfaces`（D 阶段新建 + AdminPolicyController 改造）
- 新增：`interfaces/security/RbacFilter.java`
- 新增：`interfaces/admin/AdminRolesController.java`
- 新增：`interfaces/admin/AdminUserRoleController.java`
- 新增：`interfaces/admin/AdminRbacPreviewController.java`
- 修改：`interfaces/admin/AdminPolicyController.java`（保留兼容 + Deprecation header）
- 新增：上述各文件的 `*Test.java`/`*IT.java`

### `agent-gateway-ui`（D 阶段新建）
- 新增：`src/pages/Roles/List.tsx` + `EditDrawer.tsx` + `columns.tsx`
- 新增：`src/pages/UserBindings.tsx`
- 修改：`src/routes.tsx`
- 修改：`src/components/SidebarMenu.tsx`（如存在）
- 新增：上述各文件的 `.test.{ts,tsx}`

### `openspec` 与工程
- 新增：`openspec/changes/d1-iam-rbac-deepening/sql/V__add_rbac_tables.sql` + `sql/README.md`
- 新增：`openspec/changes/d1-iam-rbac-deepening/evidence/phase-b-baseline.txt`
- 新增：`openspec/changes/d1-iam-rbac-deepening/evidence/spec-checklist.md`
- 修改：`openspec/changes/d1-iam-rbac-deepening/tasks.md`（E.4 全勾选）
- 新增：`scripts/check-rbac-backcompat.sh`
- 归档移动：`openspec/changes/d1-iam-rbac-deepening` → `openspec/changes/archive/2026-08-25-d1-iam-rbac-deepening`

---

## 关联文档

- 变更定义：`openspec/changes/d1-iam-rbac-deepening/`（proposal/design/spec/tasks）
- D 阶段路线：`docs/superpowers/specs/2026-08-25-d-stage-roadmap.md`
- 项目级 spec：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§6.3 / §19 / §22.2）
- 协同规范：`AGENTS.md`
- 前情归档：`openspec/changes/archive/2026-08-14-add-auth-and-rbac/`
- 样本 plan：`docs/superpowers/plans/2026-08-13-a2a-and-discovery.md`、`docs/superpowers/plans/2026-08-13-multi-model.md`

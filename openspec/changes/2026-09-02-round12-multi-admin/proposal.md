# Proposal: 多 Admin 账号 + 团队 RBAC（round12-multi-admin）

> **状态**：Round 12 #1 · 协作 + 数据闭环 #2
> **来源**：竞品分析报告 §六 B4 + Round 10 优化报告 §九 #2
> **借鉴**：Portkey 团队管理 / LiteLLM Team / Okta/Auth0 RBAC

## 动机

当前 admin 鉴权是**单静态串**（`X-Admin-Token`）,无法支撑多用户协作：
- 所有管理员共享一个 token,无法追踪"谁做了什么"
- 无团队/工作空间隔离,跨租户审计混淆
- 无法实现细粒度 RBAC（按角色：owner/admin/operator/viewer）

Round 12 把 admin 升级到「多 Admin 账号 + 团队/工作空间 + RBAC」三层结构,作为后续 SSO/OIDC（Round 13+）的基础。

## What

### 后端

**domain** (`gateway-domain/iam/` 新增)
- `AdminUser` record —— id / email / name / role / tenantId / status / createdAt / lastLoginAt
- `AdminRole` enum —— OWNER / ADMIN / OPERATOR / VIEWER
- `AdminStatus` enum —— ACTIVE / SUSPENDED / DELETED
- `Team` record —— id / name / ownerId / memberIds / tenantId / createdAt
- `AdminUserRepository` Port —— CRUD + findByEmail + findByRole + findByTenant
- `TeamRepository` Port —— CRUD + addMember / removeMember / findByMember

**application** (`gateway-application/iam/`)
- `AdminUserService` —— register / suspend / changeRole / list
- `TeamService` —— create / addMember / removeMember / transferOwnership

**interfaces** (`gateway-interfaces/admin/`)
- `AdminUserController` —— CRUD + 状态切换 + 角色变更（X-Admin-Token）
- `TeamController` —— CRUD + 成员管理（X-Admin-Token）

**persistence** (`gateway-infra-persistence/iam/`)
- `InMemoryAdminUserRepository` (P0) + `@ConditionalOnMissingBean`
- `InMemoryTeamRepository` (P0) + `@ConditionalOnMissingBean`
- R13 替换为 Pg 表 + bcrypt 密码哈希

### 前端（Round 12 iter-2）

- `pages/AdminUsers.tsx` —— 用户列表(角色/状态/最后登录) + 创建/停用/角色变更
- `pages/Teams.tsx` —— 团队列表 + 成员管理 + 转让所有权
- `Sidebar.tsx` —— 加"团队 / 成员"菜单项
- `routes.tsx` —— 加 /admin-users /teams 路由

### 升级 Admin Token 策略

保留向后兼容：
- `X-Admin-Token: <static>` —— 仍认作 OWNER 角色（兼容旧客户端）
- `X-Admin-Token: <admin-user-api-key>` —— 查 AdminUser 表识别角色（新增）
- `X-User-Id` —— 新增可选 header,审计可定位到具体 Admin

## Non-goals

- 不做密码 / SSO（Round 13+ 用 bcrypt / OIDC）
- 不做审计级 RBAC（审计已经 append-only,角色变更走 audit 事件）
- 不改现有 API Key / Chat 链路鉴权

## 验收

- 后端 domain + port + InMemory + Controller 全部单测绿
- 创建 AdminUser / Team 端点可调通
- 角色变更 / 成员管理端点可调通
- 前端管理页可列/筛选/操作
- verify.sh 全绿
- 评分:产品 +2 / 运营 +1

## 风险与权衡

| 风险 | 缓解 |
|---|---|
| 兼容旧 X-Admin-Token 客户端 | 默认按 OWNER 处理 + warning log;R13 强制升级 |
| 多 Admin 鉴权绕过 | 保留单一 trust anchor;新鉴权只是更细粒度 |
| 团队 owner 转让链断裂 | 转让前校验目标用户存在 + ACTIVE 状态 |
| InMemory 数据易失 | P0 接受;R13 接 Pg + bcrypt |

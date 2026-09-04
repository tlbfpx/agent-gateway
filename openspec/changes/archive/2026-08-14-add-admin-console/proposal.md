# Proposal: 管理后台（add-admin-console）

> **状态：✅ 已完成**（2026-08-14）。一期 MVP 最后一块。

## 变更概述

实现管理后台 REST 端点（§16-20）：租户/模型/API Key/RBAC/配置的 CRUD。spec §16-20。一期 MVP 最后一块——完成后核心网关 + 全部平台管理能力交付。

## What / 范围

### 做
- **gateway-interfaces** 新增 admin 控制器（`/v1/admin/*`）：
  - `AdminTenantController`（§16）：租户 CRUD（一期：最小 CRUD，InMemory 存储）
  - `AdminModelController`（§17）：模型 CRUD + 连通性测试（对接 ModelRegistry/ChatClientFactory）
  - `AdminApiKeyController`（§18）：API Key 签发/吊销/轮换（对接 ApiKeyStore）
  - `AdminRbacController`（§19）：角色管理 + 权限预览（对接 AuthorizationService）
  - `AdminConfigController`（§20）：路由/限流/特性开关查看（一期只读）
- **管理存储**：复用已有 InMemory 实现（ApiKeyStore/SessionRepository）+ 新增 InMemoryTenantStore。
- **一期最小 CRUD**（spec §10.1 声明）：核心增删改查 + 基础 RBAC，高级特性（dry-run 预览、版本 diff、灰度）留二期。

### 不做（YAGNI / 二期）
- 策略 dry-run 预览、配置版本 diff、模型灰度、组织多级树（二期）。
- 前端 UI（§12 前端 change 独立）。
- admin RBAC 角色分离（ADMIN/SUPERADMIN，一期简化为管理 token）。

## 验收标准
1. `/v1/admin/api-keys`（POST/GET/DELETE）：签发/列表/吊销，对接 ApiKeyStore。
2. `/v1/admin/models`（GET/POST/PUT/DELETE）：模型 CRUD + 连通性测试端点。
3. `/v1/admin/tenants`（POST/GET）：最小租户管理。
4. `/v1/admin/rbac/preview`：权限预览（给定 principal 看能调哪些 Agent）。
5. 条件装配，`mvn clean test` 全绿。

## 关联文档
- spec §16-20：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置：全部核心 + 平台 change（auth/persistence/observability/cost-audit）

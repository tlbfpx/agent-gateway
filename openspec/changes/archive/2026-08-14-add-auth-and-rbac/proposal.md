# Proposal: 鉴权与 RBAC（add-auth-and-rbac）

> **状态：✅ 已完成**（2026-08-14）。验收见末尾「实现结果」。

## 变更概述

实现 `gateway-infra-security`：API Key 认证通道 + Agent 级/模型级 RBAC 授权。spec §6。domain 已有 `AuthPrincipal`/`AgentGrant`/`AuthChannel`（foundation）；本 change 新增 domain `Authenticator` 端口 + infra 实现。

## 动机

编排核心（add-orchestration-and-sse）需在请求入口解析 AuthPrincipal（谁、哪个租户、授权范围），并在 Agent 调用/模型选择时做 RBAC 校验。当前无认证——任何人可调任何 Agent/模型。本 change 补 API Key 通道（一期）+ RBAC 决策。

## What / 范围

### 做
- **domain `Authenticator` 端口**（零框架）：`authenticate(ApiKey) → AuthPrincipal`（不存在/无效抛异常）。一期 API Key 通道；端口预留 SSO（二期 OIDC）。
- **domain `AuthorizationService` 端口**（零框架）：RBAC 决策——`canInvokeAgent(principal, agentName)`、`canUseModel(principal, modelId)`、`checkAndThrow(...)`。一期 Agent 级 + 模型级。
- **`gateway-infra-security`**：
  - `ApiKeyAuthenticator`：校验 `X-API-Key` → 查 key 绑定的 tenant/user/grants/allowedModels → AuthPrincipal。key 存储：InMemory（默认）+ 可选 DB/Redis（条件装配，与 session-store 模式一致）。
  - `AuthorizationServiceImpl`：基于 AuthPrincipal.agentGrants/allowedModels 判定（纵深防御：与 AgentCardPort 路由过滤 + A2A 调用前二次校验配合）。
  - 条件装配。
- **API Key 管理**：签发/吊销（一期简单：配置/内存，管理 REST 端点归 admin-console change）。

### 不做（YAGNI）
- **SSO/OIDC 接公司 IDP**（二期）：端口预留。
- **Skill 级 / 数据级 RBAC**（二期）。
- **管理后台 REST**（CRUD API Key/角色/权限）：add-admin-console change。
- **限流**（spec §8.3）：独立关注，编排/接入层 change。

## 验收标准
1. domain `Authenticator` + `AuthorizationService` 端口（零框架）。
2. `ApiKeyAuthenticator`：有效 key → AuthPrincipal；无效/吊销 → 抛异常。单测覆盖。
3. `AuthorizationServiceImpl`：canInvokeAgent/canUseModel/checkAndThrow，Agent 级 + 模型级。单测覆盖正反例。
4. 条件装配，应用空启动 contextLoads。
5. 覆盖率 ≥80%，domain 未改（除新增 2 端口）。
6. `mvn clean test` 全绿。

## 关联文档
- spec §6 认证鉴权：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`
- 前置：add-foundation-skeleton（domain AuthPrincipal/AgentGrant/AuthChannel）
- 后续：add-orchestration-and-sse（用 Authenticator/AuthorizationService）

## 实现结果（2026-08-14 完成）

| 验收项 | 结果 |
|---|---|
| domain Authenticator + AuthorizationService 端口 | ✅ 零框架，含 AuthenticationException/AuthorizationException |
| ApiKeyAuthenticator | ✅ 有效 key → AuthPrincipal(channel=API_KEY)；无效/空/吊销 → AuthenticationException；5 测试 |
| AuthorizationServiceImpl | ✅ canInvokeAgent/canUseModel/checkAndThrow，Agent 级 + 模型级，null 安全；6 测试 |
| ApiKeyStore + InMemoryApiKeyStore | ✅ findByKey（含 revoked 过滤）；二期接 DB/Redis 替换 bean |
| 条件装配 | ✅ InfraSecurityAutoConfiguration，bootstrap 接线全 6 infra，空启动 contextLoads |
| 覆盖率 ≥80% | ✅ 业务逻辑（config 排除），11 测试 |

**测试**：security 11 测试（5 认证 + 6 授权）。domain 新增 2 端口 + 2 异常（端口契约由 infra 测试覆盖）。

**实现期决策**：
1. **domain 端口而非 service 类**：Authenticator/AuthorizationService 是出站端口（infra 实现），保持六边形架构一致。
2. **ApiKeyStore 抽象**：认证依赖 key 存储，抽象为接口（InMemory 默认/DB 二期），便于条件装配替换。
3. **纵深防御**：AuthorizationService.checkAndThrow 提供强制校验语义，供编排层在 Agent 调用前/模型选择前调用（与 AuthPrincipal.canInvoke 领域判定配合）。
4. **SSO 端口预留**：Authenticator 接口一期只 API Key，但端口设计允许二期加 OIDC 分发（AuthPrincipal.channel 区分）。

# OIDC SSO 接入指南（spec 2026-09-05 §sso-oidc）

agent-gateway 用 **OpenID Connect Authorization Code Flow** 接 IdP。客户无需手填 4 个端点 URL——只需给 issuer + client-id + client-secret，剩下的由 `OidcDiscoveryClient` 自动从 `{issuer}/.well-known/openid-configuration` 拉取（spec §4 round 4）。

> 5 分钟接入。每个 IdP 都按以下 3 步走：
> 1. IdP 端：建一个 OIDC 应用，记下 `issuer` + `client-id` + `client-secret`
> 2. 网关端：环境变量或 Helm values 设 3 个字段
> 3. 测试：访问 `/login` 点「企业 SSO」按钮

---

## 通用前置条件

|项 | 要求 |
|---|---|
| agent-gateway 版本 | ≥ 0.1.0-SNAPSHOT（2026-09-05+） |
| 网关外网入口 | IdP 能回调：`{public_base}/v1/auth/oidc/callback`（HTTPS，公网可信证书） |
| 浏览器 | 现代浏览器（Chrome/Firefox/Safari/Edge 近 2 版） |
| 邮箱策略 | IdP 必须返回 `email` claim（spec §6.3）；本 ` `email_verified=true` 才自动 provisioning AdminUser |

---

## Azure AD (Microsoft Entra ID)

### 1. Azure Portal 操作

```
1. Microsoft Entra ID → App registrations → New registration
2. Name: agent-gateway-sso
3. Supported account types: "Accounts in this organizational directory only"（单租户）
   或 "Accounts in any organizational directory"（多租户）
4. Redirect URI:
   - Type: Web
   - URI: https://gateway.example.com/v1/auth/oidc/callback
5. Register
6. 记下：
   - Application (client) ID  → GATEWAY_OIDC_CLIENT_ID
   - Directory (tenant) ID     → 用作 issuer（如 https://login.microsoftonline.com/<tenant-id>/v2.0）
7. Certificates & secrets → New client secret → 记下 Value（不是 Secret ID）
   → GATEWAY_OIDC_CLIENT_SECRET
8. API permissions → Microsoft Graph → delegated → email / openid / profile → Grant admin consent
```

### 2. gateway 配置

```bash
# .env 或 docker-compose
GATEWAY_OIDC_ENABLED=true
GATEWAY_OIDC_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
GATEWAY_OIDC_CLIENT_ID=<application-client-id>
GATEWAY_OIDC_CLIENT_SECRET=<client-secret-value>
```

### 3. 测试

```
1. 浏览器访问 https://gateway.example.com/login
2. 应看到「用 Enterprise SSO 登录」按钮
3. 点击 → 跳到 Microsoft 登录页
4. 用工作账号登录 → 自动回到 /admin-users
```

---

## Okta

### 1. Okta Admin 操作

```
1. Applications → Create App Integration
2. Sign-in method: OIDC - OpenID Connect
3. Application type: Web Application
4. App integration name: agent-gateway
5. Sign-in redirect URIs: https://gateway.example.com/v1/auth/oidc/callback
7. Sign-out redirect URIs: https://gateway.example.com/login（可选）
9. Save
10. 记下：
    - Client ID     → GATEWAY_OIDC_CLIENT_ID
    - Client secret → GATEWAY_OIDC_CLIENT_SECRET
    - Issuer URL    → https://<your-okta-domain>.okta.com（默认）
      或 https://<your-okta-domain>.okta.com/oauth2/default（自定义授权服务器）
```

### 2. gateway 配置

```bash
GATEWAY_OIDC_ENABLED=true
GATEWAY_OIDC_ISSUER=https://your-okta-domain.okta.com
GATEWAY_OIDC_CLIENT_ID=...
GATEWAY_OIDC_CLIENT_SECRET=...
```

---

## Auth0

### 1. Auth0 Dashboard 操作

```
1. Applications → Create Application → Regular Web Applications
2. Name: agent-gateway
3. Allowed Callback URLs: https://gateway.example.com/v1/auth/oidc/callback
4. Save
5. Settings tab → 记下：
   - Domain              → https://<tenant>.auth0.com（→ GATEWAY_OIDC_ISSUER）
   - Client ID           → GATEWAY_OIDC_CLIENT_ID
   - Client Secret      → GATEWAY_OIDC_CLIENT_SECRET
6. （可选）Auth0 Pipeline 配 custom claims 让 email_verified=true
```

### 2. gateway 配置

```bash
GATEWAY_OIDC_ENABLED=true
GATEWAY_OIDC_ISSUER=https://<tenant>.auth0.com
GATEWAY_OIDC_CLIENT_ID=...
GATEWAY_OIDC_CLIENT_SECRET=...
```

---

## Google Workspace

### 1. Google Cloud Console

```
1. APIs & Services → OAuth consent screen
   - User type: Internal（仅组织内）
   - App name: agent-gateway
   - Scopes: openid, email, profile
2. Credentials → Create Credentials → OAuth client ID
   - Application type: Web application
   - Name: agent-gateway
   - Authorized redirect URIs: https://gateway.example.com/v1/auth/oidc/callback
3. Create
4. 记下：
   - Client ID     → GATEWAY_OIDC_CLIENT_ID
   - Client secret → GATEWAY_OIDC_CLIENT_SECRET
5. Issuer：https://accounts.google.com（固定）
```

### 2. gateway 配置

```bash
GATEWAY_OIDC_ENABLED=true
GATEWAY_OIDC_ISSUER=https://accounts.google.com
GATEWAY_OIDC_CLIENT_ID=<...>.apps.googleusercontent.com
GATEWAY_OIDC_CLIENT_SECRET=...
```

> ⚠️ Google Workspace 邮箱 `email_verified` 始终 true（仅限同组织）；外部 Google 账号返回 false → 自动 provisioning 会拒绝（spec §6.3）。

---

## Helm 一键配置

`deploy/helm/agent-gateway/values.yaml` 已内嵌 OIDC 示例段（spec §hel m-chart round 5）：

```yaml
gateway:
  oidc:
    enabled: true
    issuer: "https://login.microsoftonline.com/<tenant-id>/v2.0"
    # client-id / client-secret 必须从 ExternalSecret 注入
secrets:
  existingSecret: agent-gateway-secrets  # 含 oidc-client-id / oidc-client-secret
```

`kubectl create secret` 模板：

```bash
kubectl create secret generic agent-gateway-secrets \
  --from-literal=oidc-client-id=<value> \
  --from-literal=oidc-client-secret=<value>
```

---

## 排错 checklist

| 现象 | 排查 |
|---|---|
| 点 SSO 按钮报 404 | OIDC_ENABLED 是否设到启动环境；`/v1/auth/oidc/status` 应返回 `{enabled:true}` |
| IdP 登录后跳回时报 400 "invalid state format" | 检查 redirect_uri 是否精确匹配 IdP 配置（大小写、尾斜杠、协议） |
| IdP 登录后报 "id_token iss mismatch" | issuer 必须与 IdP 实际 issuer 字符串一致（有些 IdP 的 issuer 与登录 URL 不同） |
| 报 "JWK not found for kid=X" | IdP 轮换了密钥；OidcJwksClient 缓存 10min 后会自动重拉 |
| 用户登录后看到空白 | 检查 AdminUserAdminService.findByEmail；新用户应自动 provisioning 租户 `oidc-<domain>` |
| 报 "id_token aud mismatch" | aud claim 与 client-id 不一致；多 audience IdP 应支持数组（已实现） |
| 浏览器跳到 IdP 后立刻被重定向回 gateway | 检查 IdP 端的 callback URL 配置（包括 https / path / query） |

---

## 安全注意事项

- **务必用 HTTPS**：生产 gateway 必须有可信证书；HTTP 下 IdP 会拒绝 callback
- **client-secret  secret**：secret 应通过 External Secrets Operator / SOPS / Vault 注入；不要 commit
- **不要共享 client-secret**：每个 gateway 部署一个独立 client；轮换周期 ≤ 90 天
- **audience 严格**：client-id 唯一，不要在多个网关实例间共享
- **session TTL**：当前 24h（硬编码 `AdminAuthService.TTL_MS`）；生产应改为可配置
- **state nonce**：CSRF 防重放由 `OidcStateStore`（10min TTL，in-memory）兜底；多实例 / 重启会丢未消费 state——生产建议换 Redis

---

## 高级：多 IdP / 自定义域

agent-gateway 当前 1 个 issuer → 1 套 client 配置。多租户 SaaS 客户每租户独立 IdP（spec §3 future）：

- 路径：把 `gateway.oidc.*` 改成 `gateway.oidc.tenants.<tenant-id>.*`，由租户 → issuer 索引
- 工作量：：~200 行新代码 + 路由改造
- 优先级：P1 SaaS 化时做；自托管单租户不影响

参考 OIDC Discovery 客户端实现：`gateway-interfaces/.../auth/OidcDiscoveryClient.java`（spec §4 round 4）。
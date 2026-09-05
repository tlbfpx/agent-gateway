# Changelog

agent-gateway 的版本变更记录。Frontend `/changelog` 页面会读取本文件渲染卡片。

## [0.2.0] — 2026-09-05

首个对外可售卖版本。覆盖 P0-P1 完整能力：试用漏斗、安全治理、企业 SSO、自托管、文档。

### ✨ New Features
- **Demo 模式** — `/demo` 着陆页一键试用，无需注册；自动清理 24h
- **自助注册** — `/signup` 三字段（email/password/companyName），30 秒开通独立租户
- **首登引导** — `/settings` 检测「已登录但无 Key」状态，一键签发首把 API Key
- **审计日志导出 CSV** — `/v1/admin/audit/logs/export.csv`，SOC2/ISO27001 合规
- **OIDC SSO** — Authorization Code Flow + JWKS RS256 验签 + Discovery 自动发现端点 + RP-Initiated Logout
- **多租户越权修复** — `TenantEnforcementFilter` 强制服务端校验 X-Tenant-Id 范围 → 403 GW-1003
- **Status 页面** — `GET /status.json` 公开健康矩阵 + `/status` UI
- **Helm chart** — `deploy/helm/agent-gateway/` k8s 一键部署（Deployment + Ingress + HPA + Secret）
- **Onboarding 5 步清单** — `/getting-started` 自动检测已完成的步骤
- **Changelog 页面** — `/changelog` 按 release 倒序渲染

### 🐛 Bug Fixes
- **路径双前缀** — `/v1/admin/...` 重复 `/v1` 导致 `/v1/v1/admin/...` 404（cache/configStatus/guardrails 共 10 处）
- **Feedback 缺 X-Admin-Token** — 管理端 GET 缺 token 头 → 400
- **ConfigHistory.prune UOE** — Stream.toList 不可变 list 调 remove(0) 抛错
- **request.ts 硬编码 demo key** — 删 `DEMO_KEY` 兜底，避免任何部署都变公开演示
- **/policies 重复 key 警告** — Role.id 值对象 unwrap 成字符串
- **TS 错误 8 个** — useUrlState overload + request.ts require + 5×CostCenter props + Traces setter

### 📦 Internal
- application.yml 加 `observability.pg.*` 块
- application.yml 加 `gateway.demo.*` 块
- application.yml 加 `gateway.oidc.*` 块（含 `OidcDiscoveryClient` 自动填充端点）
- TS 编译从 9 errors → 0
- 路由巡检 41/41 全绿

### 📖 Docs
- **`docs/operators/OIDC.md`** — Azure AD / Okta / Auth0 / Google 5 分钟接入指南
- **`deploy/helm/agent-gateway/README.md`** — k8s 自托管完整指南
- **`README.md`** — 全功能介绍 + 安全/合规/部署章节

---

## [0.1.0] — 2026-08-15

### ✨ New Features
- 首批可安装版本：网关核心（chat/agents/auth）+ 管理端 UI
- 32 个页面 + 命令面板 + 快捷键 + 移动端响应式 sidebar

---

升级路径：每行 bullet 是独立 atomic commit；按需 revert。
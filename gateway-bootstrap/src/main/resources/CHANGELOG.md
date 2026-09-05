# Changelog

agent-gateway 的版本变更记录。Frontend `/changelog` 页面会读取本文件渲染卡片。

## [Unreleased] — 2026-09-05

### ✨ New Features
- **Demo 模式** — `/demo` 着陆页一键试用，无需注册；自动清理 24h
- **自助注册** — `/signup` 三字段（email/password/companyName），30 秒开通
- **Settings 首登引导** — 检测「已登录但无 Key」状态，一键签发首把 API Key
- **审计日志导出 CSV** — `/v1/admin/audit/logs/export.csv`，SOC2/ISO27001 合规
- **OIDC 单点登录** — `GET /v1/auth/oidc/login` + callback + JWKS 签名验证 + OIDC Discovery
- **多租户越权修复** — TenantEnforcementFilter 强制验证 X-Tenant-Id 范围
- **Status 页面** — `GET /status.json` 公开健康矩阵
- **Helm chart** — `deploy/helm/agent-gateway/` 一键部署 k8s（Deployment + Ingress + HPA + Secret）

### 🐛 Bug Fixes
- **路径双前缀** — `/v1/admin/...` 重复 `/v1` 导致 `/v1/v1/admin/...` 404（cache/configStatus/guardrails 共 10 处）
- **Feedback 缺 X-Admin-Token** — 管理端 GET 缺 token 头 → 400（feedback.ts 显式 headers）
- **ConfigHistory.prune UOE** — Stream.toList 不可变 list 调 remove(0) 抛错
- **request.ts 硬编码 demo key** — 删 `DEMO_KEY` 兜底，避免任何部署都变公开演示
- **/policies 重复 key 警告** — Role.id 值对象 unwrap 成字符串

### 📦 Internal
- application.yml 加 `observability.pg.*` 块对齐 HikariPgConfig
- application.yml 加 `gateway.demo.*` 块（Demo 模式开关）
- application.yml 加 `gateway.oidc.*` 块（SSO 配置）

---

## [0.1.0] — 2026-08-15

### ✨ New Features
- 首批可安装版本：网关核心（chat/agents/auth）+ 管理端 UI
- 32 个页面 + 命令面板 + 快捷键 + 移动端响应式 sidebar

---

升级路径：每行 bullet 是独立 atomic commit；按需 revert。
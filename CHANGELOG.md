# Changelog

agent-gateway 的版本变更记录。Frontend `/changelog` 页面会读取本文件渲染卡片。

## [0.4.0] — 2026-09-07

i18n 完整 + 可监控 + 可集成：海外买家 0 摩擦走完试用漏斗。

### ✨ New Features
- **i18n（zh / en）** — header 顶部语言切换按钮；antd ConfigProvider 接 enUS / zhCN locale
  · 公开页双语：/welcome /demo /signup /login /settings /pricing /contact /getting-started /changelog /legal/*
- **/welcome 公开营销首页** — Sales 分享链接首屏（Hero + 4 卡 + 信任徽章 + 3 CTA）
- **OIDC RP-Initiated Logout** — `GET /v1/auth/oidc/logout` 完整登出
- **/oauth/callback 端到端落地页** — token 走 URL fragment 落 localStorage
- **scripts/setup-public-demo.sh** — `demo.agent-gateway.com` 一键 k8s 部署
- **docs/sales/demo-instance.md** — 公开 demo 部署操作手册 + 销售话术 + 故障应急
- **/v1/info 元数据端点** — 公开 build / feature flags / GitHub 链接
- **/v1/admin/stats 业务计数** — demo / signup / active API keys
- **/v1/admin/stats/prom Prometheus 抓取** — 4 个指标可被 Grafana 看板消费
- **/v1/openapi.json 公开** — 集成方自助生成客户端 SDK
- **漏斗埋点** — `src/lib/analytics.ts` PostHog 兼容 / no-op 默认
  · 5 事件：demo_click / demo_bootstrap / signup_start / signup_success / pricing_cta_click

### 📦 Internal
- 修复 8 个 pre-existing TS 错误（useUrlState overload / CostCenter props / Traces setter 等）
- 35+ 单测（OIDC 31 + Info 4 + Demo/Signup 等）
- TS 0 错
- 路由巡检 41/41 全绿

---

## [0.3.0] — 2026-09-06

SaaS 化就绪：多租户独立 IdP 完整支持。

### ✨ New Features
- **多租户 SaaS SSO** — `gateway.oidc.tenants.<tenantId>.{issuer,client-id,client-secret}` 配置
  · 每租户独立 IdP（Azure AD 多 tenant / Okta 多 org / Auth0 多 domain）
  · 未命中 → 自动 fallback 全局配置（向后兼容）
  · state 打包 3 段（raw.returnTo.tenantId），防 SPA 切租户攻击
  · OIDC login / callback 双端接 `?tenant=` 路由到对应 IdP
- **OIDC /status 透出 tenantOverrides 计数** — SaaS 运维面板知道配了几家 IdP
- **/contact 页** — 5 个支持渠道（销售 / 安全 / 隐私 / 文档 / 社区）+ 3 档支持力度
- **/legal/{terms,privacy}** — B2B 合规占位
- **/getting-started** — 新租户 5 步上手清单（localStorage 记忆完成度）
- **/pricing** — 3 档 × 9 项功能对比 + FAQ
- **OIDC 端到端** — login → callback → /oauth/callback → admin-users 全链路跑通

### 🐛 Bug Fixes
- OIDC callback 变量名 `tenant` 冲突（query param vs redirect 路径）
- `extractReturnTo` 适配 3 段 state 格式（raw.returnTo.tenantId）

### 📦 Internal
- OIDCService tenantOverrideCount() + hasTenantOverride(tenantId) 助手
- 31 OIDC 单测（+2 多租户 state 拆解）
- TS 0 错；31/31 单测绿

---

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
- **路径双前缀** — `/v1/admin/...` 重复 `/v1` 导致 `/v1/v1/admin/...` 404
- **Feedback 缺 X-Admin-Token** — 管理端 GET 缺 token 头 → 400
- **ConfigHistory.prune UOE** — Stream.toList 不可变 list 调 remove(0) 抛错
- **request.ts 硬编码 demo key** — 删 `DEMO_KEY` 兜底
- **/policies 重复 key 警告** — Role.id 值对象 unwrap 成字符串
- **TS 错误 8 个** — useUrlState overload + request.ts require + 5×CostCenter props + Traces setter

### 📦 Internal
- application.yml 加 `observability.pg.*` 块
- application.yml 加 `gateway.demo.*` 块
- application.yml 加 `gateway.oidc.*` 块
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
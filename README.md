# Agent Gateway

> AI Agent 调用的统一网关：路由、限流、计费、审计、RBAC、缓存。
>
> 一行 Helm 起网关，5 分钟接企业 SSO。Demo 现成 · 自助注册 30 秒 · 三档定价直白透明。

| | |
|---|---|
| 版本 | [v0.2.0](https://github.com/tlbfpx/agent-gateway/releases/tag/v0.2.0) |
| License | Apache-2.0 |
| 技术栈 | Spring Boot 4.0 · Spring AI Alibaba · MiniMax / GLM / DeepSeek · Nacos A2A |
| 路由 | 41 个管理页面 + 35+ 后端 REST 端点 |
| 安全 | 多租户越权防护 + 审计 SOC2 + JWT RS256 + bcrypt |
| 部署 | 本地 / Docker / Helm / SaaS / 私有化 |

---

## 快速开始

**1. 在线试用**（最快，零本地安装）

  - 🚀 [一键试用 Demo](http://localhost:5173/demo) — 24h 自动清理
  - 📝 [自助注册](http://localhost:5173/signup) — 30 秒开通独立租户
  - 💰 [查看定价](http://localhost:5173/pricing) — Community / Team / Enterprise 三档

**2. 本地启动**

    # 后端（8080）
    MINIMAX_API_KEY=sk-... mvn -pl gateway-bootstrap spring-boot:run
    # 前端（5173，代理 /v1 → 8080）
    cd agent-gateway-ui && npm install && npm run dev

打开 http://localhost:5173 → 按 `/getting-started` 5 步清单走完，10 分钟解锁全部能力。

**3. 生产部署**

    # Kubernetes 一键（Helm chart，含 Ingress + HPA + TLS）
    helm install gateway ./deploy/helm/agent-gateway \
      --values ./deploy/helm/agent-gateway/values.yaml

详细见 [`deploy/helm/agent-gateway/README.md`](deploy/helm/agent-gateway/README.md)。

## 功能总览

**对话**（`/v1/chat`、`/v1/chat/stream`）
- SSE 流式逐字输出 · Markdown/代码块渲染 · 多轮上下文记忆（HistoryPolicy 可插拔：LastN/滚动摘要）
- 工具调用可视化（Agent 调用中/完成/失败）· 会话列表时间分组 · PII 实时脱敏（可开关）
- 消息级用量透明：每条回答显示实际命中模型（灰度分流后）+ token 估算（`done` 事件 `meta` 字段）

**模型接入**（可插拔 Provider SPI：minimax/deepseek/zhipuai/openai/兼容协议）
- 管理菜单动态配置（provider/key/厂商模型名），持久化 `data/models.json` 重启不丢
- 同名灰度组按 weight 加权分流 · 能力降级 failover（无 function-calling 时切 fallback）· 灰度分组效果对比报表（各成员请求数/延迟分位/错误率/成本，`/v1/admin/models/{id}/grayscale-comparison`）
- 版本历史 + 回滚 + 任意两版字段级 diff

**安全与治理**
- API Key 双通道（签发/吊销持久化 `data/api-keys.json`）· Key 过期时间（`expiresAt`，过期自动 401）· 模型白名单 · Agent 级 + **Skill 级 RBAC**
- 限流五维度（租户/用户/Key QPS + Agent 并发 + token 日预算）→ 429
- **多租户隔离加固**：`TenantEnforcementFilter` 强制服务端校验 X-Tenant-Id 越权 → 403 GW-1003（防止伪造 header 跨租户读）
- 运营台独立管理凭据 `X-Admin-Token`（`gateway.security.admin-token`，默认空=关闭，与用户 API Key 分离）
- 审计日志（认证/授权/限流/Agent 调用）append-only + 查询端点 + **CSV 导出**（SOC2/ISO27001 合规，`/v1/admin/audit/logs/export.csv`）
- Webhook 事件推送（HMAC 签名 + 指数退避重试 + 死信队列）

**企业 SSO**：[`docs/operators/OIDC.md`](docs/operators/OIDC.md) — OIDC Authorization Code Flow，支持 Azure AD / Okta / Auth0 / Google Workspace；Discovery 自动发现端点（5 分钟接入）
- 登录入口：`/login` 页显示「用 Enterprise SSO 登录」按钮
- 自动 provisioning：首次 SSO 登录按 email 自动建 AdminUser + bcrypt 防密码登录

**Demo / 自助注册**
- `/demo` 一键试用，24h 自动清理，零注册成本
- `/signup` 30 秒自助开通（email/password/companyName 三字段）
- `/settings` 首登引导「一键签发首把 API Key」

**可观测**：Micrometer 指标（`/actuator/metrics`）· OpenAPI 3.0（`/v1/openapi.json`）· readiness/liveness 分离（`/v1/ready` `/v1/health`）· **运维 status 页面**（`/status`，公开 `GET /status.json`）

**预算治理**：预算 80%/100% 两级告警（AlertCenter + Webhook 推送，去重）· 超限动作可配 BLOCK（默认 429）/ DOWNGRADE（降级到 fallbackModel 继续服务）

**部署**：[`deploy/helm/agent-gateway/`](deploy/helm/agent-gateway) — Helm chart 一键部署到 k8s（Deployment + Service + Ingress + HPA + Secret，helm lint 0 错）

**运营**：模型管理 · Webhook 订阅 · 审计查询 · 配置版本/回滚/diff（全部前端可视化）· **Changelog 页面**（`/changelog`，按 release 倒序）

## 运维

    ./verify.sh              # 一键门禁：编译+全模块测试+依赖方向断言
    mvn clean install      # 全量构建
    # 优雅停机 30s；data/ 目录为运行时数据（已 gitignore）

## 文档

> ⚠️ **生产部署前必读**：[`docs/known-limitations.md`](docs/known-limitations.md) —— 内存态存储范围 + 插件隔离边界 + 长期搁置项的当前状态

- **`docs/known-limitations.md`**（生产部署前必读）
- **`docs/operators/OIDC.md`**（Azure AD / Okta / Auth0 / Google 5 分钟接入指南）
- 设计 spec：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§1-29）
- 变更史：`openspec/changes/`（OpenSpec 四件套） + **`CHANGELOG.md`**（前端 `/changelog` 渲染）
- 协同规范：`AGENTS.md`

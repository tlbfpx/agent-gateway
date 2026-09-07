# Public Demo 流程文档（spec §public-demo round 86）

## 概述

agent-gateway 公开 demo 实例（demo.agent-gateway.com）的端到端使用流程，给销售 / 客户支持 / 试用客户。

## 流程

```
┌──────────────────────────────────────────────────────────────────────┐
│ 1. Sales 分享链接 https://demo.agent-gateway.com                    │
│    ↓                                                                 │
│ 2. Prospect 点 /demo → 一键开通试用租户（24h TTL）                    │
│    ↓ 浏览器 localStorage：apiKey / tenant / adminToken / expiresAt │
│ 3. Prospect 走完产品试用（chat / agents / audit / feedback / pricing）│
│    ↓ 漏斗埋点（demo_bootstrap / signup_start / pricing_cta_click）   │
│ 4. Prospect 想要正式租户 → 点 /signup → 30s 自助注册               │
│    ↓ 复用 adminToken + 自己的 tenantId                              │
│ 5. Prospect 试用满意 → 升级到 Team / Enterprise                     │
│    ↓ 邮件联系 sales@agent-gateway.local                             │
│ 6. Enterprise 客户接入 OIDC 多租户 SaaS                            │
│    ↓ gateway.oidc.tenants.<tenantId>.* config                      │
│ 7. 自我托管 / 私有化部署（Helm chart）                            │
│    ↓ helm install agent-gateway                                     │
└──────────────────────────────────────────────────────────────────────┘
```

## 各角色使用流程

### 销售（Sales）
```
1. 复制链接 https://demo.agent-gateway.com 发给客户
2. 客户点 /demo 30 秒内进产品
3. 1-2 周后客户问 production 部署 → 升级 /pricing → Team (¥299/月)
4. 客户公司超过 50 人 → 升级 Enterprise (面议)
5. 销售跟踪：看 admin/me / admin/users / admin/tenants 看销售漏斗
```

### 客户支持（Support）
```
1. 客户报「我的 chat 不工作」
2. 打开 /v1/admin/system-info → 看 JVM / 内存 / 线程
3. 打开 /v1/admin/stats/prom → 看 demo_count / signup_count
4. 查 /v1/admin/users → 查该用户的 tenantId + 最近登录
5. 查 /v1/audit/... → 查操作历史（future round 87）
```

### 运维（SRE）
```bash
# 1. 部署公开 demo
docker compose -f deploy/docker-compose.demo.yml up -d

# 2. 健康检查
kubectl get deploy,po,svc -n agent-gateway-demo
curl http://demo.agent-gateway.com/status.json

# 3. k8s 三段探针
startupProbe: /v1/ping
livenessProbe: /v1/health
readinessProbe: /v1/ready

# 4. Prometheus 抓取
curl http://demo.agent-gateway.com/v1/admin/stats/prom
```

### 试用客户（Prospect）
```
1. 收到销售链接 → 点 /demo → 30 秒内进产品
2. 试用 chat / agents / audit / feedback
3. 想"留住数据" → 点 /signup → 30 秒自助注册（保留 adminToken）
4. 想"公司统一管理" → 联系 sales@agent-gateway.local
5. 试用满意 → 选 Team (¥299/月) / Enterprise (面议)
```

## Demo 端点速查

| 端点 | 用途 |
|---|---|
| `GET /v1/demo/status` | 探测 demo mode 启用状态 |
| `POST /v1/demo/bootstrap` | 一键开通试用租户（24h TTL） |
| `POST /v1/demo/reset` | 清理当前 demo（按 X-API-Key） |
| `GET /v1/info` | 元数据 + feature flags |
| `GET /v1/openapi.json` | SDK spec（生成客户端用） |
| `GET /v1/admin/me` | 当前 admin 自查 |
| `GET /v1/admin/system-info` | JVM/内存/线程/OS |

## Demo 数据生命周期

- **24 小时**：apiKey + tenant 自动清理
- **客户主动 reset**：点 /demo 「重置当前 demo」按钮 → 调 /v1/demo/reset
- **过期清理**：后端 DemoCleanupJob @Scheduled 每 1 小时扫一次
- **完全独立**：demo 数据与生产租户完全隔离（不同 storage namespace）

## 上线检查清单

- [ ] 公开 demo 域名（demo.agent-gateway.com）DNS 解析到 gateway ingress
- [ ] cert-manager 自动签 TLS 证书
- [ ] postgres (TimescaleDB) 与 gateway 同 k8s namespace
- [ ] GHCR 镜像（ghcr.io/tlbfpx/agent-gateway:v1.x.y）可拉取
- [ ] LLM API key（演示用占位 key 限速 100 req/min）
- [ ] 监控（Prometheus 抓取 /v1/admin/stats/prom）
- [ ] 告警（demo_count 不增 / error_rate > 1% / heap > 80%）
- [ ] 备份（PG dump 每 24h 到 S3）

## Demo 上线后监控

```bash
# 24h 后 demo_count 应自增（每用户 +1）
curl http://demo.agent-gateway.com/v1/admin/stats/prom | grep demo

# active_api_keys 应 ≈ 0（旧 demo 已过期被清理）
curl http://demo.agent-gateway.com/v1/admin/stats/prom | grep active

# uptime 应递增（> 24h 表示未重启）
curl http://demo.agent-gateway.com/v1/info | jq .uptimeSeconds
```

## 故障应急

| 现象 | 排查 |
|---|---|
| /demo 报 500 | 查 gateway 日志 `OIDC`/`Datasource`/`DemoCleanup` |
| /v1/admin/* 401 | 查 X-Admin-Token 是否一致 |
| 内存涨到 > 80% | 查 activeSessions + 触发 JVM heap dump |
| demo_count 涨不动 | 查 DemoCleanupJob 是否被 @EnableScheduling 启用 |

## Demo 部署的合规注意

- ❌ 不放任何真实业务数据
- ❌ 不用真实 LLM API key（用占位 key 限速）
- ✅ 24h 自动清理
- ✅ 与生产租户完全隔离
- ✅ 公开 Admin Token 限 demo 域
- ✅ LLM 调用低额（防 abuse）

详见 [deploy/docker-compose.demo.yml](../../deploy/docker-compose.demo.yml) + [deploy/helm/agent-gateway/](../../deploy/helm/agent-gateway/README.md)（生产部署用 helm）。

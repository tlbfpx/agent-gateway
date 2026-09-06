# 公开 demo 实例部署（spec §public-demo）

## 为什么需要

每次 Sales 想让潜在客户试用 agent-gateway，**最关键的一步是给对方一个能直接点的链接**。`demo.agent-gateway.com` 满足：
- 客户 0 安装，**30 秒进产品**
- 自动 24h 清理（每次访问一个新 demo 租户）
- 数据隔离（每个访问者一个独立 demo-* 租户）
- 走 HTTPS + 公开可访问

这是 SaaS 漏斗的入口。没有它，每次 demo 都要 Sales 录屏或买家本地搭。

## 部署（一次）

前置：
- k8s 集群（任意云或本地 minikube）
- 公网域名 `demo.agent-gateway.com` 指向 cluster ingress
- cert-manager（自动签 TLS 证书）
- `kubectl` 已配好上下文
- 至少一个 LLM API key（minimax / openai / deepseek / zhipu）

执行（一行命令）：

```bash
export GATEWAY_ADMIN_TOKEN=$(openssl rand -hex 32)
export POSTGRES_PASSWORD=$(openssl rand -hex 16)
export MINIMAX_API_KEY=sk-...   # 你的真实 key
./scripts/setup-public-demo.sh demo.agent-gateway.com
```

脚本会：
1. 创建 namespace `agent-gateway-demo`
2. 写入 K8s Secret（admin token / PG 密码 / LLM key）
3. `helm install` agent-gateway + 自动 ingress + TLS + demo mode 开
4. 等待 rollout 完成
5. curl `/status.json` 确认 200

完成后客户访问 https://demo.agent-gateway.com：
- `/demo` → 一键开试用租户（24h TTL）
- `/signup` → 30 秒自助注册
- `/pricing` `/contact` `/legal/*` `/changelog` `/getting-started` 全可访问
- `/status` 公开

## 运维 checklist

| 频率 | 任务 |
|---|---|
| 每天 | 看 `kubectl logs` + 监控 `/actuator/health` |
| 每周 | 备份 `data/audit/` 到 S3（PG dump） |
| 每月 | 升级 image tag 到最新 release（`helm upgrade --set image.tag=v0.x.y`） |
| 季度 | 轮换 admin token / LLM API key / cert-manager 证书 |

## 关键环境变量

| 变量 | 用途 | 来源 |
|---|---|---|
| `GATEWAY_ADMIN_TOKEN` | 32 字节随机 | 手动 `openssl rand -hex 32` |
| `POSTGRES_PASSWORD` | PG 密码 | 同上 |
| `MINIMAX_API_KEY` | LLM 调用 | 公司 MiniMax 控制台 |
| `OIDC_ISSUER` | SSO IdP（可选） | 走 Okta / Auth0 / Azure AD |
| `IMAGE_TAG` | 镜像 tag | 默认 v0.3.0，可用 latest |

## 安全注意

- demo.agent-gateway.com **不应包含任何真实业务数据**
- 默认 demo 模式 24h 清理
- 严格网络隔离：建议单独 k8s 集群 + 仅可出公网到 LLM API
- `GATEWAY_ADMIN_TOKEN` 只给 SRE，不进 git
- LLM key 必须是**演示用 key**（设低 rate limit / 单独计费）

## 多 demo 集群（如需）

```bash
NAMESPACE=demo-eu IMAGE_TAG=v0.3.0 ./scripts/setup-public-demo.sh eu.demo.agent-gateway.com
NAMESPACE=demo-us IMAGE_TAG=v0.3.0 ./scripts/setup-public-demo.sh us.demo.agent-gateway.com
```

每个集群独立 DB + 独立 demo 租户池。

## 销售话术

> 直接把 `https://demo.agent-gateway.com` 发给客户。
> 客户点 `/demo` → 30 秒内完整功能（chat / agents / 审计 / 多租户）。
> 不需要下载 / 安装 / 注册会议。
> 24h 后自动清理，下次访问又是一个新的干净实例。

## 故障应急

| 现象 | 排查 |
|---|---|
| 502 Bad Gateway | `kubectl get ingress` 查 ADDRESS；ingress controller 状态 |
| /demo 报 500 | `kubectl logs` 看 gateway 日志；`GATEWAY_ADMIN_TOKEN` 是否对得上 |
| /status.json 报 404 | ingress 还没路由到 gateway service；`kubectl describe ingress` |
| demo 模式 24h 后没清理 | 查 `kubectl logs` 有没有 `demo.cleanup.removed`；可能 DemoCleanupJob 没启动 |
| 客户端说 401 | 检查 GATEWAY_ADMIN_TOKEN 同步（前后端都用同一份） |

详见 [`deploy/helm/agent-gateway/README.md`](../../deploy/helm/agent-gateway/README.md) 排错章节。
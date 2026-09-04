# agent-gateway Helm Chart

Kubernetes 部署包（spec 2026-09-05 §helm-chart）。

## 前置

- Kubernetes 1.23+（autoscaling/v2 HPA + networking.k8s.io/v1 Ingress）
- 已装 metrics-server（HPA 依赖）
- 已装 nginx-ingress（或别的 Ingress Controller；改 `ingress.className`）
- 已装 cert-manager（如果要自动签 TLS）
- 一个外部 Postgres / TimescaleDB（生产推荐）；dev/staging 可用 Bitnami chart 同 namespace 起一个

## 快速开始

```bash
# 1) 准备 namespace
kubectl create ns agent-gateway

# 2) 准备 Secrets（生产推荐用 External Secrets Operator / SOPS）
kubectl -n agent-gateway create secret generic agent-gateway-secrets \
  --from-literal=observ-postgres-password='YOUR_PG_PASSWORD' \
  --from-literal=gateway-admin-token='$(openssl rand -hex 32)' \
  --from-literal=openai-api-key='sk-...' \
  --from-literal=dashscope-api-key='sk-...' \
  --from-literal=deepseek-api-key='sk-...' \
  --from-literal=zhipu-api-key='sk-...' \
  --from-literal=minimax-api-key='sk-...'

# 3) 安装 chart
helm install gateway ./deploy/helm/agent-gateway \
  --namespace agent-gateway \
  --values ./deploy/helm/agent-gateway/values.yaml \
  --set config.observability.storage.jdbcUrl='jdbc:postgresql://my-pg-host:5432/agentgateway' \
  --set secrets.existingSecret=agent-gateway-secrets
```

## 升级

```bash
helm upgrade gateway ./deploy/helm/agent-gateway \
  --namespace agent-gateway \
  --reuse-values  # 保留上次 values
```

## 卸载

```bash
helm uninstall gateway --namespace agent-gateway
```

## 配置矩阵

| 场景 | 关键 values |
|---|---|
| 生产最小化 | `replicaCount=2`, `autoscaling.enabled=true`, `ingress.enabled=true`, `demo.enabled=false` |
| 开发/演示 | `replicaCount=1`, `autoscaling.enabled=false`, `ingress.enabled=false`, `demo.enabled=true` |
| 内嵌 PG | 加 bitnami/postgresql 子 chart；`config.observability.storage.jdbcUrl` 指向 service DNS |
| 灰度发布 | 加 istio VirtualService / Argo Rollouts 之类上层；本 chart 只管基础 deployment |

## 关键 values 说明

- `image.repository` / `image.tag` — 镜像位置；CI 推 `ghcr.io/tlbfpx/agent-gateway:<sha>`
- `resources.limits` — CPU/memory cap；触发 OOM 时先看 metrics-server 是否抓到
- `ingress.hosts` — 改为你自己的域名；记得同步 `ingress.tls` 让 cert-manager 签证书
- `secrets.existingSecret` — 生产强烈建议指向 External Secrets Operator 管理的 Secret；本 chart 自带 Secret 仅 dev 用
- `config.gateway.apiKeys` — 启动期注入的静态 API key；多租户场景用 admin REST 动态签发而非这里
- `demo.enabled` — 自助注册 / Demo 模式总开关（spec 2026-09-04 §demo-mode）；生产关掉
- `jvm.heapMax` — 容器内存 50% 留 headroom；不要超过 `resources.limits.memory` 70%

## 升级 Gateway 镜像

```bash
helm upgrade gateway ./deploy/helm/agent-gateway \
  --set image.tag=v0.1.1 \
  --reuse-values
```

HPA 会自动扩缩；滚动升级走默认 `RollingUpdate`（maxSurge=25%, maxUnavailable=25%）。

## 排错

| 现象 | 排查 |
|---|---|
| Pod Pending | `kubectl describe pod` 看 Events；通常是 resources 不够或 nodeSelector 不匹配 |
| CrashLoopBackOff | `kubectl logs <pod> --previous`；多半是 Secret 缺失或 PG 连接错 |
| 502 Bad Gateway | `kubectl get ingress` 看 ADDRESS；Ingress Controller 是否就绪 |
| Admin 端点 401 GW-1401 | 检查 `secrets.gatewayAdminToken` 是否与后端配置一致 |
| Demo 模式不可见 | `values.yaml` 里 `demo.enabled: true`；或者 kubectl edit configmap 改 `gateway.demo.enabled` |
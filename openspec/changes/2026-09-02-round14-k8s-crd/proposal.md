# Proposal: K8s CRD Gateway / Route（round14-k8s-crd）

> **状态**：Round 14 #3 · 平台化补强 · 部署形态
> **来源**：竞品对照矩阵 §II row 9 + Round 13 报告 §九 R14+
> **借鉴**：Higress / kgateway / Envoy Gateway API

## 动机

agent-gateway 当前是 Spring Boot 单体（🟡 部署形态）;
竞品中 Higress / Envoy / kgateway 全部支持 K8s CRD 让运维通过 `kubectl apply` 管理。

P0 目标：定义 `Gateway` + `Route` CRD,双向同步到 Spring config;
P0 不引入 Fabric8（避免重依赖 + K8s cluster 测试成本),用 InMemory simulator。

## What

### CRD 规范

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: agentgateways.gateway.agentgateway.io
spec:
  scope:Namespaced
  group: gateway.agentgateway.io
  names:
    kind: AgentGateway
    plural: agentgateways
    shortNames: [agw]
  versions:
    - name: v1alpha1
      served: true
      storage: true
      additionalPrinterColumns: [...]
```

```yaml
apiVersion: gateway.agentgateway.io/v1alpha1
kind: AgentGateway
metadata:
  name: prod-gateway
  namespace: default
status:
  conditions:
    - type: Ready
      status: "True"
  observedGeneration: 1
```

### 后端

**domain** (`gateway-domain/k8s/`)
- `GatewaySpec` record —— name / namespace / listeners / replicas
- `RouteSpec` record —— name / namespace / gatewayRef / match / backend / weight
- `GatewayStatus` record —— conditions + observedGeneration
- `K8sGatewayPort` —— list/watch gateway CR + list route CR + update status

**application** (`gateway-application/k8s/`)
- `GatewayReconciler` —— 把 K8s GatewaySpec 翻译成 Spring gateway config
- `RouteReconciler` —— 把 K8s RouteSpec 应用到路由表

**interfaces** (`gateway-interfaces/k8s/`)
- `GatewayController` —— 兼容 K8s API:/apis/gateway.agentgateway.io/v1alpha1/namespaces/{ns}/agentgateways
- `RouteController` —— 同上 /agentroutes

**persistence** (`gateway-infra-persistence/k8s/`)
- `InMemoryK8sGatewayStore` —— 模拟 K8s API server(CRD 注册表)
- `K8sGatewaySimulator` —— 接收 CRUD,与 Spring gateway 配置双向同步

## Non-goals

- 不依赖 Fabric8(避免重依赖 + K8s cluster 测试)
- 不接真实 K8s cluster
- 不做 informer / leader election(P0 单实例)

## 验收

- domain + application + interfaces + InMemory 完整
- CRD spec YAML 文件
- CRUD 端点 + 模拟 K8s API 行为
- 翻译到 Spring gateway 配置(实测端口 8080 起来后能路由)
- 单测覆盖

## 风险

| 风险 | 缓解 |
|---|---|
| 与 Spring config 双向同步复杂度 | P0 单向(K8s → Spring);反向留 R15 |
| 真实 K8s API 兼容 | 仅模拟 /apis/... 端点 + JSON;spec 完全照搬 |
| Spring config 翻译错误 | CRD 字段严格校验 + 错误码 |
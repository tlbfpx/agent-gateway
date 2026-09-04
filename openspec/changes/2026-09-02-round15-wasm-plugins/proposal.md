# Proposal: 插件系统 v1（round15-wasm-plugins）

> **状态**：Round 15 #1 · 平台化 · 扩展性
> **来源**：竞品对照矩阵 §II row 10 + Round 14 报告 §七
> **借鉴**：Envoy ext_proc / Higress Wasm / Cloudflare Workers

## 动机

agent-gateway 唯一 🟡 项是"扩展性/插件"——只支持 Spring Bean 内置,无法热加载第三方代码。
竞品中 Envoy/Higress/Cloudflare 全部支持插件市场,Envoy ext_proc + Wasm 尤其灵活。

R15 #1 设计:**Plugin SPI + Capability-based sandbox**(Java ServiceLoader 实现)。
R15 #2 计划 swap 到 Chicory Wasm 运行时(架构不变,只换 Adapter)。

## What

### domain (`gateway-domain/plugin/`)
- `PluginDescriptor` record —— id / name / version / description / capabilities / format(JAVA|WASM)
- `PluginCapability` enum —— HEADER_INJECT / BODY_TRANSFORM / RATE_LIMIT / AUDIT / COMPRESS / LOG
- `PluginRequest` record —— path / method / headers / body / tenant
- `PluginResponse` record —— headers / body / status / mutated
- `Plugin` Port —— load / unload / list / execute

### application (`gateway-application/plugin/`)
- `PluginManager` —— ServiceLoader 发现 + 启动加载 + 内存注册
- `PluginSandbox` —— 调用链:request → Plugin1 → Plugin2 → ... → response
- `PluginExecutor` —— 单 plugin 调用包装(超时 + try/catch 隔离)

### 4 个官方样本插件
1. **HeaderInjectPlugin** —— 给响应加 X-Gateway: agent-gateway
2. **CompressPlugin** —— body > 1KB 自动 gzip(标记 Content-Encoding)
3. **AuditPlugin** —— 把每次请求记到 audit log
4. **RateLimitPlugin** —— 单 tenant 100 req/s 限速(内存计数器)

### interfaces (`gateway-interfaces/admin/`)
- `PluginController` —— `GET/POST /v1/admin/plugins` + `/v1/admin/plugins/{id}/execute`

## Non-goals

- 不做 Wasm 字节码加载(R15 #2 swap Chicory)
- 不做 marketplace 上传/签名
- 不做 hot reload via file watch

## 验收

- domain + application + 4 官方插件 + 接口 + UI
- 单测覆盖 ≥ 80%
- verify.sh 全绿
- 评分:产品完整度 +1(竞品对照 row 10 🟡→✅)

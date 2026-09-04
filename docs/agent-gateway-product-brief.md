# Agent Gateway · 产品说明书

> **公司级 LLM/Agent 通用网关 · 一处接入、全局可控**
> 版本：v0.1（GA）· 文档日期：2026-08-31

---

## 一、产品定位

Agent Gateway 是面向 **企业 AI 中台**的 LLM/Agent 统一网关。在多模型、多 Agent、多业务线的复杂场景下，把分散在 OpenAI / Anthropic / 智谱 / DeepSeek / 自研 Agent 等异构源的调用，统一收敛到一条可观测、可治理、可计费的入口。

**一句话价值**：让企业用 **一个网关**，替代 **N 个 Provider Key + N 套限流脚本 + N 份账单**。

---

## 二、核心价值主张

| 业务痛点 | Agent Gateway 解法 |
|---|---|
| 多模型供应商分散管理，Key 满天飞 | **统一 Provider SPI**（minimax / deepseek / zhipuai / openai / 兼容协议），管理台一站配置 |
| 大模型成本不可控，账单对不上 | **Virtual Key + Stripe 预付费 + 用量对账**，每条调用记录可追溯到 Key/用户/模型/Agent |
| 灰度发布无抓手，错了全量炸 | **同名模型按权重分流 + 失败降级 failover + 灰度对比报表**（错误率/延迟分位/成本） |
| Agent 越权调用、Skill 暴露 | **Agent 级 + Skill 级 RBAC**，运营台可视化策略编辑 |
| 突发流量击穿后端 | **五维限流**（租户/用户/Key QPS + Agent 并发 + token 日预算）→ 429 优雅降级 |
| 跑量后预算超支无告警 | **预算 80%/100% 两级告警** → AlertCenter + Webhook 自动通知 |
| 审计、告警、回调各做一套 | **审计 append-only + AlertCenter + Webhook（HMAC + 重试 + DLQ）三件套** |
| 故障无现场可还原 | **Trace UI**：PG 表格 + waterfall + 一键 replay |
| 上游应用改造成本高 | **OpenAI 兼容端点**——`POST /v1/chat/completions` 零改造接入 |

---

## 三、核心功能

### 3.1 模型接入（Provider SPI）
- **可插拔架构**：`Provider` 接口 + `minimax / deepseek / zhipuai / openai` 五个内置实现 + OpenAI 兼容协议适配
- **动态配置**：运营台 `/models` 页面增删改查 Provider、Key、模型映射，持久化 `data/models.json`，重启不丢
- **多模型路由**：同一逻辑模型名下挂多 Provider，按 `weight` 加权分流
- **能力降级 failover**：function-calling 等能力缺失时自动切 fallback 模型

### 3.2 对话与流式（SSE）
- `POST /v1/chat` 同步 / `POST /v1/chat/stream` SSE 流式逐字输出
- 多轮上下文：`HistoryPolicy` 可插拔（LastN / 滚动摘要）
- 工具调用可视化、Markdown/代码块渲染、会话列表时间分组
- PII 实时脱敏（可开关）

### 3.3 OpenAI 兼容端点 ⭐
- `POST /v1/chat/completions` **完全兼容 OpenAI 协议**
- 上游用 `openai-python` / `openai-node` 只需改 `base_url`，**零业务代码改动**
- 流式按 SSE 输出 `data: {...}\n\n`，`[DONE]` 收尾
- 错误体格式化为 OpenAI 风格 `{ error: { message, type, code } }`
- 适用场景：把 OpenAI 生态应用（Cursor/Cline/Continue 等 IDE 插件 + 第三方 SaaS）平滑迁移到企业私有大模型

### 3.4 虚拟 Key + 计费闭环 ⭐
- **Virtual Key**：为每个客户/团队签发独立 Key，可设置 `balanceCny`（预付余额）+ `monthlyQuotaCny`（月配额）
- **Stripe 充值**：前端"Top up"按钮 → 输入金额 → Stripe Checkout Session → webhook 回调自动入账
- **用量对账**：每次调用产生一条 usage record（`recordId / tenant / user / model / tokensIn/Out / cost`），前端对账页可视化
- **超额保护**：余额/配额不足时自动 402 拒绝或降级到 fallback 模型

### 3.5 Trace UI ⭐
- **列表页** `/traces`：按 traceId / 服务 / 状态 / 时长过滤，PG 分页
- **详情侧拉**：waterfall 时间线可视化 span 嵌套结构
- **Replay**：一键重放历史请求，便于复现 bug 与压测
- 适用：上线排查、故障复盘、容量评估

### 3.6 RBAC（角色权限）
- **Agent 级 + Skill 级**两级权限
- 运营台可视化策略编辑：用户 × 资源类型（Agent/Skill/Model）分段控件 + 实时下拉（来自注册中心）
- 内置 admin / operator / viewer 三角色 + 自定义角色
- 权限决策带中文 plain-language 解释（"为什么这次调用被拒"）

### 3.7 限流与预算
- **五维限流**：租户 / 用户 / Key QPS + Agent 并发 + token 日预算 → 超限 429
- **预算治理**：80% / 100% 两级告警，触发动作可配 `BLOCK`（默认 429）或 `DOWNGRADE`（降级到 fallbackModel）
- 告警去重，避免轰炸

### 3.8 可观测与告警
- **Micrometer 指标**：`/actuator/metrics`
- **OpenAPI 3.0**：`/v1/openapi.json` 自动导出
- **readiness / liveness** 分离：`/v1/ready` `/v1/health`
- **AlertCenter**：firing/resolved 双状态 + 持久化 + URL 过滤
- **NotificationCenter**：60s 轮询 firing 告警 + DLQ 新增事件，按 alertId/事件去重
- **死信队列（DLQ）**：Webhook 失败 5 次入 DLQ，运营台一键 redeliver

### 3.9 审计与合规
- **审计日志 append-only**：认证 / 授权 / 限流 / Agent 调用 / 配置变更 五类
- 服务端查询与分页（`tenant / type / result / from / keyword / offset`）
- 审计导出 CSV（财务/合规对接）

### 3.10 灰度与配置治理
- **灰度对比报表**：按 weight 分流的 A/B 视图，自动结论建议（错误率差 x 倍 → 建议提权/全量；样本不足时明示）
- **配置版本历史**：每次配置变更入库，可回滚任意版本 + 字段级 diff
- **配置热更新**：模型 / 限流 / Webhook / RBAC 等无需重启即可生效

---

## 四、技术架构亮点

| 维度 | 实现 |
|---|---|
| 协议接入 | Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1 |
| Agent 互联 | Nacos A2A 协议，服务注册发现 + 健康检查 |
| 后端架构 | DDD 分层（domain 不依赖 infra）+ 端口-适配器模式 + EDA 事件总线 |
| 流式输出 | SSE + Reactive Streams（WebFlux） |
| 数据持久化 | 内置 `data/*.json` 文件存储（gitignored）+ 可替换为 JPA |
| 缓存与降级 | 语义缓存（Round 5 后可扩展）+ 容量降级策略 |
| 部署 | 单 jar（`gateway-bootstrap-0.1.0-SNAPSHOT.jar`）+ Docker / K8s / 物理机 |
| 优雅停机 | 30s 排空进行中请求 |
| 依赖方向 | 严格 `domain ← application ← infra`，编译期断言 |

**技术栈**：Spring Boot 4.0 · Spring AI Alibaba · Nacos A2A · WebFlux · Micrometer · OpenAPI 3.0 · React 18 + TypeScript + antd · Vite

---

## 五、应用场景

### 5.1 大型企业 AI 中台
多业务线共用大模型，财务/合规/安全/平台团队统一管控。
- 财务：用量对账 + 成本中心
- 合规：审计日志 + Webhook 推送到 SIEM
- 安全：RBAC + 限流 + PII 脱敏
- 平台：配置中心 + 灰度发布

### 5.2 SaaS 厂商接入多家大模型
不希望绑定单一 LLM 供应商，按价格/能力/区域灵活切换。
- Provider SPI 一行配置切换
- 同名灰度按 weight 分流
- 失败自动 failover

### 5.3 Agent 创业公司
快速把 A2A 协议的自家 Agent 暴露成 REST/HTTP 服务。
- `POST /v1/admin/agents` 注册 → 立即获得 `/v1/chat` 调用入口
- Nacos 服务发现自动同步 Agent 列表
- 心跳 + 健康检查 + 自动下线

### 5.4 OpenAI 兼容迁移
现网有 OpenAI 生态应用（Cursor/Cline/Continue/内部工具），希望切换到私有部署。
- 改 `base_url` 一行
- 业务代码 0 改动
- 配额/RBAC/审计白拿

---

## 六、竞品对比

| 能力 | LiteLLM Proxy | Portkey AI Gateway | Cloudflare AI Gateway | **Agent Gateway（本产品）** |
|---|---|---|---|---|
| 多 Provider 路由 | ✅ 100+ | ✅ | ✅ | ✅ 5 内置 + 兼容协议 |
| OpenAI 兼容端点 | ✅ | ✅ | ✅ | ✅ |
| 虚拟 Key + 计费 | ⚠️ 基础 | ✅ | ❌ | ✅ **+ Stripe 集成** |
| 预付费充值 | ❌ | ⚠️ 实验 | ❌ | ✅ **Stripe Checkout** |
| Agent 注册 + A2A | ❌ | ❌ | ❌ | ✅ **Nacos A2A** |
| Trace UI | ⚠️ 基础 | ✅ | ⚠️ 基础 | ✅ **PG + waterfall + replay** |
| Agent/Skill RBAC | ❌ | ⚠️ 粗粒度 | ❌ | ✅ **两级 RBAC** |
| 灰度对比报表 | ❌ | ⚠️ | ❌ | ✅ **自动结论** |
| 配置版本/回滚 | ❌ | ❌ | ❌ | ✅ |
| Webhook DLQ + 重投 | ⚠️ | ⚠️ | ❌ | ✅ |
| 部署形态 | 自托管 | SaaS | SaaS | **自托管（私有化友好）** |
| 数据主权 | 自托管 | 第三方 | 第三方 | ✅ **全在自己手里** |

**差异点**：本产品是为**企业 AI 中台**设计的全栈方案，不是单纯的 LLM Proxy。特别在 **Agent/Skill 治理 + 虚拟 Key 计费 + Trace 治理闭环**三个维度，与 LiteLLM/Portkey 形成代差。

---

## 七、部署与集成

### 7.1 一键启动
```bash
# 后端（8080）
MINIMAX_API_KEY=sk-... mvn -pl gateway-bootstrap spring-boot:run

# 前端（5173，代理 /v1 → 8080）
cd agent-gateway-ui && npm install && npm run dev
```

### 7.2 镜像部署
- 单 jar：`gateway-bootstrap-0.1.0-SNAPSHOT.jar`（含所有依赖）
- 可观测性：`docker-compose.observability.yml`（Prometheus + Grafana + Loki 一键起）

### 7.3 接入新业务
```bash
# 1. 注册模型
curl -X POST http://gw/admin/models -H "X-Admin-Token: ..." -d '{...}'

# 2. 签发 Virtual Key
curl -X POST http://gw/admin/virtual-keys -H "X-Admin-Token: ..." -d '{...}'

# 3. 业务侧调用
curl -X POST http://gw/v1/chat/completions \
  -H "Authorization: Bearer vk_..." \
  -d '{"model": "gpt-4o", "messages": [...]}'
```

### 7.4 OpenAI 兼容接入（零改造）
```python
# Python
from openai import OpenAI
client = OpenAI(base_url="https://your-gw.com/v1", api_key="vk_...")
resp = client.chat.completions.create(model="gpt-4o", messages=[...])
```
```js
// Node
import OpenAI from 'openai';
const client = new OpenAI({ baseURL: 'https://your-gw.com/v1', apiKey: 'vk_...' });
```

---

## 八、安全与合规

- **API Key 双通道**：用户 API Key（业务调用）+ `X-Admin-Token`（运营管理）严格分离
- **Key 过期**：每条 Key 可设 `expiresAt`，过期自动 401
- **PII 实时脱敏**：可选开关，按正则规则 + 可扩展
- **审计 append-only**：所有敏感操作不可篡改
- **Webhook HMAC 签名**：接收方可验签防伪造
- **失败重试 + DLQ**：5 次指数退避，运营台一键 redeliver
- **优雅停机**：30s 排空进行中请求，避免数据丢失
- **依赖方向编译期断言**：避免 domain 反向依赖 infra 引入隐式耦合
- **数据主权**：所有数据（Key/审计/用量/告警）存储在客户自己的基础设施，**不外传任何调用日志**

---

## 九、套餐建议（建议定价分级）

| 套餐 | 适用 | 月费 | 用量额度 | 关键能力 |
|---|---|---|---|---|
| **Free** | 个人开发者 / PoC | ¥0 | 100K tokens/月 | 全部基础能力、社区支持 |
| **Team** | 10 人内小团队 | ¥999/月 | 5M tokens/月 | + 灰度 + Trace + Webhook |
| **Business** | 中型企业 / 业务线 | ¥9,999/月 | 50M tokens/月 | + RBAC + 预算治理 + Stripe 集成 |
| **Enterprise** | 大型集团 / 私有化 | 面议 | 不限 | + 私有化部署 + SSO + SLA + 7×24 支持 |

> *价格仅为建议，最终以商务确认为准。*

---

## 十、技术支持

| 渠道 | 响应 |
|---|---|
| 文档 | docs.superpowers/ + openspec/changes/ |
| 邮件 | support@agent-gateway.example.com |
| 企业微信群 | Team/Business 含群支持 |
| 7×24 On-call | Enterprise 含 |

---

## 十一、Roadmap（未来 6 个月）

- **Q3**：语义缓存（命中降本 30~70%）、Guardrails（PII/jailbreak/toxicity 阻断）、SSO/OIDC 集成
- **Q4**：A/B 实验框架（流量分桶 + 显著性检验）、多租户 Org/Team/User 层级、细粒度计费维度（按业务线/部门）
- **Q1+**：Request-level replay 强化、MCP server 接入、ConfigMap/Nacos 热重载

---

**Agent Gateway · 让企业 AI 用得稳、看得见、花得值。**

> 本说明书为 v0.1 GA 版本，技术细节以 `/v1/openapi.json` 与运营台 `/admin/openapi` 实时文档为准。
> 销售联系：sales@agent-gateway.example.com

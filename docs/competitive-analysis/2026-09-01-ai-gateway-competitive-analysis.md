# AI Gateway 竞品分析与改进建议

> 日期：2026-09-01 · 视角：竞品对照 + 内部 UX 痛点扫描
> 配套参考：[[agent-gateway-module-map]]、[[sprint2-p53-replay-resilience4j]]、Round 7 优化报告
> 范围：运营体验 / 用户体验 / 产品功能三个维度提出改进建议
> 与现有优化轮（Round 1–9）正交——后者在已知缺口矩阵上推进，本报告从**外部视角**补盲点

---

## 一、agent-gateway 现状摘要（截至 Round 9）

### 已成熟的能力（达到商业产品 90 分+）
| 维度 | 已实现 |
|---|---|
| **路由/模型** | 多 provider（MiniMax/DeepSeek/GLM/OpenAI 兼容）、灰度分流、能力降级 failover、版本回滚、字段级 diff |
| **可观测** | Micrometer/Actuator、Trace 水位线、Replay（生产级，加密 payload + diff + 压测）、OpenAPI 3.0 |
| **缓存** | 语义缓存 L1（精确） + L2（向量）、命中率面板、PII 阻断写入 |
| **安全** | API Key 双通道、Admin Token 独立、过期时间、Agent+Skill RBAC、5 维限流、Guardrails（PII/Jailbreak/Toxicity/Tool 策略） |
| **预算/计费** | 80%/100% 两级告警、超限 BLOCK/DOWNGRADE、Cost Center（含对账子页） |
| **审计/事件** | append-only 审计、Webhook（HMAC + 指数退避 + DLQ） |
| **运维** | 配置热重载、版本 diff/回滚、XLSX+Parquet 导出、空状态引导 |
| **对话 UX** | SSE 流式、Markdown/代码块渲染、多轮记忆（LastN/滚动摘要）、工具调用可视化、消息级用量透明 |

### 与商业 AI Gateway 对齐度（粗略估计）
- vs Portkey：70%（缺 prompt playground、virtual key 托管、feedback/标注、模型评测）
- vs LiteLLM：75%（缺 router 智能路由、budget UI、embedding 路由）
- vs Cloudflare AI Gateway：80%（缓存、Guardrails、metrics 都有；但缺 Workers AI 集成、Workers Logs）
- vs Higress（阿里）：60%（缺 WAF、AI 插件市场、阿里云原生集成）
- vs Envoy AI Gateway：65%（缺 ext_proc 服务网格、kgateway 集成）

> **判断**：功能广度上已达"中上"，但**横向深度**（尤其 UX 细节、AI 特有场景、智能化能力）仍有显著空间。

---

## 二、竞品对比矩阵

> 6 个产品 × 11 个维度。✅ 已确认 / 🟡 部分支持 / ❌ 缺失 / ❓ 待核实
> 行对齐 §I 现状摘要；列对照"6 个公开产品"
> 维度数：1) 协议与厂商覆盖 2) 路由/智能路由 3) 缓存 4) Guardrails/安全 5) 限流/预算 6) 可观测 7) Prompt/Playground 8) 协作/反馈 9) 部署形态 10) 扩展性/插件 11) 协议兼容(MCP/A2A)

### 2.1 矩阵总览

| 维度 | agent-gateway (本项目) | Portkey | LiteLLM | OpenRouter | Cloudflare AI Gateway | Higress | Envoy AI Gateway |
|---|---|---|---|---|---|---|---|
| 1. 协议/厂商覆盖 | ✅ OpenAI 兼容 + MiniMax/DeepSeek/GLM/A2A | ✅ 200+ LLM，统一 OpenAI/Anthropic 格式 | ✅ 100+ provider，最广 | 🟡 仅路由 + 自有上游，自带模型池 | ✅ Workers AI + 自带 OpenAI 兼容端点 | ✅ 兼容 OpenAI/Higress AI 协议，插件适配 | ✅ OpenAI/AWS Bedrock/Azure，多 provider ext_proc |
| 2. 路由/智能路由 | 🟡 灰度分流 + capability failover；缺 cost/latency 智能策略 | ✅ Configs (fallback/load-balance/single) + 条件路由 | ✅ Router 模式（simple-routing/cost-based/rate-limit-aware） | 🟡 按 model 路由；无显式策略 DSL | 🟡 无应用层路由（按 URL 走 CF 规则） | ✅ 通过 Wasm/Go/JS 插件自定义 | ✅ 基于 Envoy route + ext_proc LLM filter |
| 3. 缓存 | ✅ L1 精确 + L2 向量语义缓存 + PII 阻断 | ✅ simple-cache + semantic-cache（新） | 🟡 支持 Redis cache，需自部署 | ❌（不缓存第三方上游响应） | ✅ Cache API + Vectorize 语义缓存 | 🟡 需自配 Redis；插件市场有 cache 插件 | ❌（cache 由 ext_proc 自行实现） |
| 4. Guardrails/安全 | ✅ PII/Jailbreak/Toxicity + Tool 策略（Round 9） | ✅ Guardrails（自营 + 第三方厂商） | 🟡 仅 content filter 回调；正式 Guardrail 框架偏弱 | 🟡 仅 moderation 透传，无独立 Guardrails | ✅ 集成 Cloudflare WAF + 内容扫描 | ✅ WAF + AI 插件市场 + 自定义插件 | 🟡 ext_proc 可挂自定义 Guardrail；非开箱 |
| 5. 限流/预算 | ✅ 5 维限流 + 80/100% 告警 + BLOCK/DOWNGRADE | ✅ Virtual Keys + budget/rate-limit | ✅ Budget/Rate-limit + per-team/per-key | 🟡 用户层 credit 体系；非网关维度 | ✅ Rate limiting rules（Cloudflare 层） | ✅ Sentinel/OpenSumi 限流 | 🟡 Envoy RL + ext_proc budget，需自实现 |
| 6. 可观测 | ✅ Micrometer + Replay（生产级）+ OpenAPI | ✅ Logs + 100+ 集成 + Observability dashboard | ✅ LiteLLM Proxy 日志 + callback hooks | 🟡 基础请求日志 | ✅ Workers Logs + Analytics Engine | ✅ 全链路 Trace + Dashboard | ✅ Envoy access log + OTLP |
| 7. Prompt/Playground | ❌ 缺失（§六 A4 列入 P1） | ✅ Prompt 模板 + Playground + 版本/A-B | 🟡 仅 proxy，不含 Playground | ✅ Playroom（playground）+ 模型对比 | ❌（无 UI 调试台） | ❌（网关职责） | ❌（网关职责） |
| 8. 协作/反馈 | ❌ 缺失（§六 B3/B4 列入 P1/P2） | ✅ 团队/组织/feedback | 🟡 仅 user/team 模型，无 feedback 流 | 🟡 基础账号 + credit | ❌（无协作功能） | ❌（网关职责） | ❌（网关职责） |
| 9. 部署形态 | ✅ Spring Boot 单体 + Docker；K8s 待补（C5） | ✅ SaaS + 自部署（企业版） | ✅ 自部署为主（Docker/Helm） | 🟡 仅 SaaS | ✅ SaaS（绑定 CF 账号） | ✅ Higress（K8s/容器/Serverless） | ✅ K8s-native（kgateway/Istio 集成） |
| 10. 扩展性/插件 | 🟡 内部 Spring Bean（Round 9 Guardrails 框架） | ✅ 自定义 Guardrail hooks | 🟡 Callback 钩子 | 🟡 仅 credit/usage 回调 | 🟡 Workers 脚本 | ✅ 插件市场（Go/JS/Wasm）+ AI 插件 | ✅ ext_proc + Wasm filter（最强扩展点） |
| 11. 协议兼容(MCP/A2A) | 🟡 A2A 已支持；MCP 列入 Round 12 | 🟡 A2A/MCP 客户端集成（早期） | 🟡 MCP 集成（2025） | 🟡 第三方工具调用，无显式 MCP | ❌ | 🟡 Higress AI 协议 + 社区 MCP 适配 | 🟡 ext_proc 可桥接，无开箱 |

> 简评：
> - **功能广度** agent-gateway 已逼近 LiteLLM/Portkey；但在 Playground/协作/反馈/插件市场三条纵深上落后。
> - **部署形态** agent-gateway 是 Spring Boot 单体，不及 Higress/Envoy 的 K8s-native 路径——这是 §九 Round 12 选 K8s CRD 的依据。
> - **协议兼容** 是行业新风口（MCP 2025→2026 事实标准），各家都在补齐，agent-gateway 的 A2A 已有基础不算晚。

### 2.2 11 维度详注（针对最不熟的两列）

> 防止矩阵过宽，详注只覆盖与本项目 §I/§六/§九 强相关的两条目；其余条目在矩阵已自描述。

**OpenRouter（最不像"网关"的竞品）**
- 本质：模型聚合 + 路由 + 按量计费，**不暴露传统网关 API 治理概念**（无独立 Guardrail/缓存层/插件）。
- 与 agent-gateway 重叠面：仅"多 provider + 统一 OpenAI 协议"（矩阵第 1 行）。
- 启示：它最强的产品力是"Playroom / 模型对比 / 一键切换"，直接对应 §五 DX 第 1 条 Playground——这是 agent-gateway 应当学的一条。

**Cloudflare AI Gateway**
- 关键事实：定位"Workers 前置网关"，能力集中在"缓存 + 日志 + 限流 + 成本可见"——与 agent-gateway 80% 重合。
- 优势：Vectorize 语义缓存 + Workers Logs 体验显著优于自部署组件。
- 启示：§六 A2 "Predictive Cache" 借鉴 Vectorize；§九 Round 10 的 Prompt Playground 借鉴 Workers 生态的"5 分钟跑通"体验。

### 2.3 ❓ 待核实条目（避免在调研不足时给错结论）

> 下面这些条目是矩阵里**置信度低**的格子，需要后续单独 web 调研填实，但不影响 §九优先级决策。

| 条目 | 当前标记 | 需核实点 |
|---|---|---|
| OpenRouter 是否自营缓存 | ❌ | 是否在 2025-2026 引入 simple-cache 透传（OpenRouter 历来不缓存第三方响应） |
| Higress 缓存能力 | 🟡 | 默认插件市场是否含"语义缓存"vender（阿里云 AI 套件） |
| Envoy AI Gateway 缓存 | ❌ | ext_proc 官方示例是否带 cache filter；若有需升级为 🟡 |
| Portkey semantic-cache 时间 | ✅ | 上线时间（2024 末/2025 初），用于定位我们"晚了一代"的程度 |
| LiteLLM embedding 路由 | 🟡 | 是否支持多 embedding model fallback 策略 DSL |
| Envoy AI Gateway kgateway 集成 | ✅ | 是否已 GA（非 preview），影响 §九 Round 12 K8s 路径选择 |
| Higress AI 协议与 MCP 关系 | 🟡 | 是否独立协议；与 MCP 兼容声明出处 |
| Cloudflare Workers AI 多模态支持 | 🟡 | 是否包含图像/语音模型，影响 §五 #9/#10 优先级 |
| Portkey prompt 版本管理发布 | ✅ | GA 时间，作为 §六 A4 时间锚点 |
| OpenRouter Playroom 是否支持 compare | 🟡 | 是否支持 A/B 并排输出，影响 DX Playground 设计 |

## 三、内部 UX 痛点摘要（详见附录 B）

> 完整证据见**附录 B**（10 个问题 + 文件:行号）

**核心结论**：UI 层 80% 的"复杂度债务"集中在 4 个点——
1. **重复 boilerplate**（5 个 List 页面各自实现 fetch+loading+error）
2. **错误兜底缺失**（toast 3 秒消失 + 无整页 ErrorState）
3. **Loading 5 种风格混用**（Spin/Skeleton/EmptyState 切换/`Table loading`/自定义 div）
4. **Onboarding 是孤岛组件**（`Onboarding.tsx` 定义了但没人 import）

**关键 bug**：
- `Dashboard.tsx:104-117` `hasWarn` 永 false，`HEALTH_LABEL['slow']` 死代码
- `MarkdownView.tsx:50-53` 复制按钮 DOM 不存在，事件委托永远命中失败
- `Traces.tsx:48-55` 列表筛选全部不持久化到 URL
- `lib/request.ts:65` Demo key 默默写入 — PM 需决定是否保留

---

## 四、运营体验改进建议

> 面向"管理员/运维/合规"角色的日常工作

### 优先级 P0（本周可做）
1. **全局搜索 / 命令面板** —— 顶部 ⌘K 弹窗，跨页搜索 Agent / Key / Trace / 配置。
   - 现状：每个页面独立搜索框，跨实体查找需手动切换页面
   - 证据：Audit.tsx、Traces.tsx、Webhooks.tsx 各自实现查询表单
2. **系统状态条 / 健康总览顶部置顶** —— 当前 Health 页面是单独路由（`/health`，94 行）。
   - 建议：Dashboard.tsx 顶部加 4 个状态徽章（API/DB/Cache/Provider），颜色直读
3. **API Key 创建向导化** —— 当前 ApiKeys/List.tsx 缺少"创建"按钮触达路径
4. **变更通知横幅** —— 配置热重载、Guardrails 规则更新时顶部 toast + 历史回顾入口
5. **导出调度** —— 当前导出同步生成，超大数据集（Trace 全量）会超时；补"导出任务" + 完成后下载链接

### 优先级 P1（下个 sprint）
6. **Runbook 一键跳转** —— Alert 触发时，告警卡内提供"查看 Runbook" / "执行预案"按钮（跳到 Workflows 页面）
7. **配额预测** —— Dashboard 上加"按当前速率，X 天后耗尽预算"的预测条
8. **多租户视图** —— Admin 当前是单租户；如要做 SaaS 化，需加租户切换 + 跨租户只读视图
9. **审计 + Trace 联动** —— 在审计日志条目上"查看关联 Trace"直接跳 Traces 页带 filter
10. **Webhook 测试台** —— 当前 Webhooks.tsx 403 行，测试发送大概率要写代码；加"模拟发送"按钮

### 优先级 P2（季度级）
11. **SSO / OIDC 接入**（目前 Admin Token 仅静态串）
12. **审计日志归档到 S3 / OSS**（append-only + 防篡改哈希链）
13. **变更审批流**（重要配置修改走二次确认 + 审批工作流）
14. **合规报告导出**（GDPR / 等保 / SOC2 模板）
15. **Prometheus / OpenTelemetry exporter 一等公民化**（当前依赖 Spring Boot Actuator）

---

## 五、用户体验改进建议

> 面向"开发者 / 最终用户 / 业务方"三类

### 开发者体验（DX）
1. **Playground / 模型调试台** —— 页面级 prompt playground
   - 选 provider → 选模型 → 输入 system+user → 调温度/topP/maxTokens → 看流式输出 → 看 token + 延迟
   - 可保存为"模板"，可对比两个模型并排输出（Portkey 的"Compare Mode"、OpenRouter Playground 都是标配）
   - 价值：把"调模型"从 Postman / curl 解放出来
2. **一键复制 cURL / Python / JS SDK** —— 任何 API 调用处提供代码片段生成（许多页面已有 SDK 但缺少生成器）
3. **OpenAPI 客户端生成** —— `/v1/openapi.json` 已暴露，但前端页面缺少"下载 openapi-generator 产物"按钮
4. **错误码字典** —— 错误响应携带 `errorCode`（GW-1xxx 等）但 UI 缺少点击跳转文档
5. **交互式 Webhook 调试器** —— 见 §四 #10

### 最终用户（Chat 页）
6. **历史会话搜索** —— 当前 Chat.tsx 782 行，会话列表按时间分组但缺少全文检索
7. **引用 / 工具结果可视化增强** —— 当前工具调用可视化已做，可加：
   - 折叠/展开记忆内容
   - 工具调用耗时高亮（哪个工具慢）
   - 中断 / 重试该步的按钮（断点续传）
8. **导出对话**（Markdown / PDF / JSON）—— 给客户成功/合规团队
9. **语音输入 / 输出**（TTS + ASR）—— 客服场景刚需
10. **多模态输入**（图片 OCR / 附件上传）

### 业务方 / 客户成功
11. **Dashboard 改为可配置 widget** —— 当前 588 行硬编码；可拖拽 + 保存视图
12. **对比报表** —— 模型灰度对比已有 `/grayscale-comparison`，但 UI 缺少"选定时间段 + 维度对比"
13. **客户账单 API** —— SaaS 化路径：让客户拉自己的账单 JSON
14. **使用排行 TopN** —— "哪些 Agent / 用户消耗最多"是 PM 最常问的

---

## 六、产品功能改进建议

> 面向"补齐缺失能力 / 引入竞品亮点"

### A. 智能化（让 gateway "懂"）
| # | 功能 | 借鉴 | 价值 |
|---|---|---|---|
| A1 | **Auto Router** —— 按 cost / latency / quality 自动选模型 | LiteLLM Router | 用户只写"我要好/便宜/快"，网关决策 |
| A2 | **Predictive Cache** —— LLM 预测 query 相似度预热 | Cloudflare Vectorize | 命中率 +10~30% |
| A3 | **Fallback Chain 可视化编排** | Portkey Configs | 当前 capability failover 是硬编码，UI 拖拽编排 |
| A4 | **Prompt 版本管理 + A/B** | Portkey Prompts | prompt 作为一等公民 |
| A5 | **Embedding 路由** —— 多 embedding 模型 fallback | LiteLLM | RAG 场景必备 |
| A7 | **Token 压缩 / Prompt Caching（厂商级）** | Anthropic / OpenAI prompt cache | 同一 prompt 前缀复用，成本 -50% |

### B. 协作化（让团队可用）
| # | 功能 | 借鉴 | 价值 |
|---|---|---|---|
| B1 | **Prompt Playground 多人协作** | Portkey | prompt 工程师 + PM 一起调 |
| B2 | **数据集 / 评测集管理** | Langfuse / Helicone | 上传数据集 → 自动评测 → 报告 |
| B3 | **Feedback 标注**（👍/👎 + 备注） | Langfuse / Portkey | 收集真实用户反馈回流训练 |
| B4 | **团队 / 组织管理** | Portkey / LiteLLM | 多用户 + 角色 + 工作空间 |
| B5 | **审计可追溯到具体身份** | 当前 Admin Token 是单 key | 多人协作需多 Admin 账号 |

### C. 平台化（让 gateway 可扩展）
| # | 功能 | 借鉴 | 价值 |
|---|---|---|---|
| C1 | **插件市场 / 自定义 Guardrail 插件** | Higress 插件市场 | 让用户写 Wasm / JS 插件注册 |
| C2 | **WebAssembly Filter** | Envoy / Higress | 比 Spring Bean 更轻量，更易分发 |
| C3 | **多协议接入**（MCP / A2A / OAP） | 当前只有 A2A | MCP 是 2026 事实标准 |
| C4 | **Sidecar / Service Mesh 模式** | Envoy / Istio | 让 gateway 也能跑在 Pod 内 |
| C5 | **CRD / Kubernetes Operator** | kgateway | 用 K8s CRD 描述 Gateway / Route |
| C7 | **Terraform Provider** | 各云厂商 | IaC 化管理 |

### D. 数据化（让运营有据）
| # | 功能 | 借鉴 | 价值 |
|---|---|---|---|
| D1 | **自定义 Dashboard / 报表** | Grafana | 把 dashboard 数据导出为 PDF 报告 |
| D2 | **异常检测 / 自动告警规则** | Datadog | 基于历史自动发现异常 |
| D3 | **SLO 跟踪** | SLO/SLI | 99% 请求 < 2s 这类目标跟踪 |
| D4 | **业务事件漏斗** | 产品分析 | "prompt → 工具调用 → 完成" 漏斗 |
| D5 | **LLM-as-a-judge 自动评测** | Langfuse | 用 LLM 评 LLM 输出 |

### E. 体验化（让配置变简单）
| # | 功能 | 借鉴 | 价值 |
|---|---|---|---|
| E1 | **零代码配置新 Provider** —— 选协议 → 填 baseUrl+key → 测试连通 | LiteLLM "Add Model" | 当前配置需重启或手动写 JSON |
| E2 | **模板库**（推荐 Guardrail 规则集、prompt 模板、限流套餐） | Portkey Library | 让新用户 5 分钟用起来 |
| E3 | **Onboarding 向导**（首次部署全屏引导） | Stripe Dashboard | 新用户体验关键 |
| E4 | **Schema 迁移工具**（v1 配置一键升级 v2） | Prisma Migrate | 避免"升级即破坏" |
| E5 | **API Key 自助 + 配额自助** | OpenRouter | 让开发者自助开通，省去运营介入 |

---

## 七、优先级矩阵（ROI × 紧迫度）

```
            高紧迫 ↑
                    │
       [P0 立刻]    │    [P1 本季]
        A1 Auto Rtr  │    B3 Feedback
        E1 零码配置  │    B4 团队管理
        B2 数据集    │    D1 自定义报表
        E3 Onboard   │    A4 Prompt版本
                    │
   ─────────────────┼──────────────────  → 高 ROI
                    │
       [P2 下季]    │    [P3 长线]
        C5 K8s CRD  │    C1 插件市场
        C7 TF Prov  │    C2 Wasm Filter
        D3 SLO      │    D5 LLM 评测
        B5 多 Admin │    A6 Token压缩
                    │
            低紧迫 ↓
```

---

## 八、不建议做的事（避免 scope creep）

- ❌ **做完整 Langfuse / Helicone 的功能** —— 那些是 observability 平台，跟 gateway 是上下游关系；agent-gateway 应聚焦"网关治理"，深度集成 Langfuse 而不是重建
- ❌ **做 prompt IDE** —— Prompt IDE 是 IDE 产品（Cursor / Continue），gateway 提供 SDK 桥接而非 IDE
- ❌ **做模型训练 / fine-tune 平台** —— 远离 gateway 职责
- ❌ **做完整 IAM 系统** —— SSO/OIDC 接 Okta/Auth0 即可
- ❌ **做完整 BI 工具** —— Dashboard 数据可同步到 Grafana / Metabase

---

## 九、3 条短期 actionable 路线（结合 Round 10/11）

> 假设每个 sprint 2 周

### Round 10：开发者体验 + 模型路由智能化（2 周）
- [ ] Prompt Playground 页面（Models/List.tsx 子页）
- [ ] Auto Router 域服务（按 cost/latency 选模型）
- [ ] 一键 cURL/Python 代码生成（任意 API 页加按钮）
- [ ] OpenAPI 客户端产物下载按钮

### Round 11：协作 + 数据闭环（2 周）
- [ ] Feedback 标注（👍/👎 + 备注）端点 + UI
- [ ] 数据集 / 评测集管理（上传 JSONL → 一键评测）
- [ ] Prompt 版本管理 + A/B 测试
- [ ] 团队 / 多 Admin 账号

### Round 12：平台化开胃菜（3 周）
- [ ] K8s CRD Gateway / Route（用 Fabric8）
- [ ] MCP 协议接入（除 A2A 外）
- [ ] Terraform Provider 雏形

---

## 十、风险与权衡

| 风险 | 缓解 |
|---|---|
| Auto Router 引入不可预期成本（选了贵模型） | 用户配 budget 上限 + dry-run |
| Feedback 数据合规（用户内容被存储） | 默认脱敏 + TTL + 显式 opt-in |
| 多 Admin 引入权限复杂度 | 复用现有 RBAC（roles/permissions 表已存在） |
| K8s CRD 与现有 Spring 配置两套 | 双向同步：CRD 是 source of truth，Spring config 是镜像 |
| 评测集引入 LLM 评测成本 | 默认仅本地规则评测，LLM 评测 opt-in |
| 插件市场分发治理（恶意插件） | 强制签名 + 沙箱（Wasm 比 JVM Bean 安全） |

---

## 附录 A：参考资料 & 调研置信度

> §II 矩阵已含 6 产品 × 11 维度对比，附录只列**关键来源** + **置信度说明**

### A.1 调研方法
- 高置信（✅）：来自 Round 1–9 commit 历史 + verify.sh 验证 + 项目自身 README
- 中置信（🟡）：基于背景知识 + 之前各 Round 报告引用过的事实
- 低置信（❓）：见 §II 矩阵的 10 条待核实清单，需后续单独 web 调研填实，**不影响 §九 优先级决策**

### A.2 关键资料链接（待补实）
| 产品 | 官方文档 | 开源仓库 |
|---|---|---|
| Portkey | portkey.ai/docs | github.com/Portkey-AI/gateway |
| LiteLLM | docs.litellm.ai | github.com/BerriAI/litellm |
| OpenRouter | openrouter.ai/docs | —（闭源） |
| Cloudflare AI Gateway | developers.cloudflare.com/ai-gateway | —（闭源） |
| Higress | higress.cn / help.aliyun.com | github.com/alibaba/higress |
| Envoy AI Gateway | envoyproxy.io/docs | github.com/envoyproxy/ai-gateway |

### A.3 §II 简评（来自 agent 总结）
- **功能广度** agent-gateway 已逼近 LiteLLM/Portkey；**Playground/协作/反馈/插件市场**四条纵深上落后
- **部署形态** 不及 Higress/Envoy 的 K8s-native 路径 → §九 Round 12 选 K8s CRD 的依据
- **协议兼容**（MCP）是行业新风口，各家都在补齐，agent-gateway 的 A2A 已有基础不算晚

## 附录 B：内部 UX 痛点证据（来自 UI 代码逐行扫描）

> 扫描范围：`src/pages/` 下 29 个文件 + `lib/markdown.ts` + `components/chat/MarkdownView.tsx`
> 按 ROI 排序（P0 在前）

### B-1. List 页面 100% 重复 boilerplate（loading/error/empty/Table 四件套各写一遍）
- **证据**：`Models/List.tsx:56-98` `ApiKeys/List.tsx:42-63` `Roles/List.tsx:77-95` `Webhooks.tsx:51-81` `Agents.tsx:79-107` — 4 处各自实现 `setLoading/setError/load()` + 三处 `try/catch` + `message.error`
- **改进**：抽出 `useResourceList(fetcher, options)` hook；`components/framework/EmptyState.tsx` 已实现，缺统一调用方

### B-2. 全局错误兜底缺失（网络挂掉时表格区域持续空白，无整页 ErrorState）
- **证据**：
  - `Models/List.tsx:92-97,419` — catch 后 setError，但 `{error ? <ErrorState> : <Table>}` 二选一，网络长期不可达时只显示空白表格
  - `Traces.tsx:82-88,161-218` — catch 后只 setError，Table 仍尝试渲染空数组 + Spin，无 ErrorState
  - `ApiKeys/List.tsx:393` — ErrorState 与 Table 同时渲染（与 Models 相反做法）
- **改进**：在 `lib/request.ts` 顶部统一注入全局 `message.error`；AppShell 包 `QueryErrorBoundary`；toast 改侧边常驻通知中心

### B-3. Loading 占位 5 种风格混用
- **证据**：`Dashboard.tsx:210` 用 `SkeletonPage` / `Traces.tsx:280` 用裸 `Spin` / `Health.tsx:62` 用 `EmptyState description={loading?'查询中…':'暂不可用'}` / `Agents.tsx:429` 同套路 / `Models/List.tsx` 等全走 `<Table loading={loading}>` — 表格外层无占位
- **改进**：定义 `useListSkeleton()`（推荐骨架屏+行 shimmer），强制 List hook 内部使用

### B-4. Onboarding 组件是孤岛（Chat 外首次访问没有引导弹窗）
- **证据**：`Onboarding.tsx:17-38` 定义了 3 步（Key/模型/聊天），但 `grep -r "Onboarding" src/pages` 无 import — 只有 Settings 调用 `RestartOnboardingButton`；Chat 之外的新用户看不到引导
- **改进**：把 `<Onboarding />` mount 到 `AppShell` 顶层，按 `useOnboarding().isActive` 自动浮起；空状态 CTA 串联到下一步

### B-5. 筛选 → URL 持久化不一致（Traces 完全不持久化，Audit 只持久 keyword）
- **证据**：`Traces.tsx:48-55` URL 只存 `traceId`，列表模式全 useState 刷新即丢 / `Audit.tsx:43-44` 只读 `keyword`，`range/type/result` 未读 URL / `CostCenter.tsx:64-98` 是双向绑定的范例（但团队未复用）
- **改进**：抽 `useUrlState` hook；Audit/Traces 顶部改"时间区间+类型" chip

### B-6. Sidebar 信息架构失衡（运营组硬塞 16 项，5 个菜单共用一个 Safety 图标）
- **证据**：`Sidebar.tsx:51-101` 仅 4 组，运营组塞 16 项 / `:82-86` 5 个菜单共用 `<SafetyOutlined />` / "开发者"组只有 `/api` 一项却单独成组
- **改进**：拆出独立"安全 & 权限"组（5 项）；换不同 icon（角色=SafetyCertificate、策略=Lock、用户绑定=UserSwitch）；面包屑缺失（AppShell 无 Breadcrumb）

### B-7. Dashboard 健康判断写反（`'slow'` 状态不可达，`HEALTH_LABEL['slow']` 是死代码）
- **证据**：`Dashboard.tsx:104-117` `let hasWarn = false` 循环里只检查 `s === 'error'`，**没有 `'warning'` 分支**赋值；`if (allUp && ...) setHealthStatus('up')` → `if (hasWarn)`（永 false）→ 永远落到 `else if (Object.keys(comps).length > 0) setHealthStatus('down')`；`HEALTH_LABEL['slow'] = '部分降级'` 是死代码
- **影响**：`Dashboard.tsx:459-468` `status === 'warning'` 分支无法命中；用户看不到"部分降级"状态
- **改进**：补 `if (Object.values(map).includes('warning')) setHealthStatus('slow')`

### B-8. Chat Markdown 代码块无语法高亮（纯黑底+白字）
- **证据**：`lib/markdown.ts:200` `<pre class="md-pre"><code>${escapeHtml(text)}</code></pre>` 仅 escapeHtml，无 token 着色 / `components/chat/MarkdownView.tsx:78-80` CSS 显式声明 `code { color: inherit }` **禁止**子元素样式 / 无 `prismjs`/`highlight.js`/`shiki` 依赖
- **改进**：引入 shiki 客户端 lazy load，或自写 50 行 tokenizer（Python/JS/Bash）

### B-9. 复制按钮在事件委托里依赖 inline CSS 但 DOM 不存在
- **证据**：`MarkdownView.tsx:50-53` `__html: html + COPY_BTN_STYLE` 把整段 CSS 拼进 innerHTML / `:26-40` click handler 找 `.md-copy-btn` / `markdown.ts:200` **根本没生成 `.md-copy-btn` 元素** / 真正可用的是 `MarkdownView.tsx:93-120` 的 `CodeBlock` 组件，但 Chat 走 `renderMarkdown` 路径（`Chat.tsx:592`）**从未用 CodeBlock**
- **改进**：要么在 `markdown.ts:200` 后 append `<button class="md-copy-btn">`，要么 Chat 切到 `<CodeBlock>`

### B-10. Cost Center 没有"对比/分桶/同期"维度，是单期表
- **证据**：`CostCenter.tsx:213-254` 4 张 StatCard 全是单期数字 / `:257-267` Tabs 只切维度不切时间 / `:315-433` CostTable 是纯表格，无 recharts/echarts 任何图 / Dashboard 已有 `<TrendPanel>`（`Dashboard.tsx:219`）**未复用**
- **改进**：加 1 张折线图（7/30 天每日成本）+ 1 张模型占比饼图，复用 `TrendPanel`；Budgets/RateLimit 同样缺图

### 次要发现（次轮处理）
- **Demo key 自动预填**：`lib/request.ts:65` 首次访问写入 `sk-demo-primary-0001` 到 localStorage — 生产风险，需 PM 决定是否保留
- **硬编码租户白名单**：`Audit.tsx:159-163`、`ApiKeys/List.tsx:407-411` 写死 `primary/tenant-b/tenant-c`，不拉 `/admin/tenants`，扩展性差
- **Dashboard 30s 自动刷新无暂停按钮**：`Dashboard.tsx:130` 死循环刷新，会打断编辑表单；Traces 有开关（`Traces.tsx:69,151-155`），不一致
- **Audit 错误码字符串匹配**：`Traces.tsx:223-225` 用 `msg.includes('503') || msg.includes('持久化存储')` 误判风险

### UI 痛点优先级建议
`B-1 → B-2 → B-7 → B-10 → B-8 → B-4 → B-6 → B-5 → B-3 → B-9`
（B-1 技术债 ROI 最高，B-9 影响面最小）
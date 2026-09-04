# Proposal: 企业级 Agent 通用网关

> 项目级提案（project proposal）。单个变更的提案在各 `changes/<change>/proposal.md`。

## 动机（Why）

公司当前各业务系统分散对接 LLM 厂商和自建 Agent，缺乏统一入口与管控：调用方重复实现认证、限流、成本核算；Agent 能力对用户不可发现、可观测性缺失；多租户隔离无保障。网关作为「智能编排器」统一入口，内置 LLM 通过 Function Calling 动态调用注册在 Nacos 的远程 Agent，实现**一次接入、全网共享、按需编排、统一治理**。

## 范围（What）

### 做什么（一期 MVP，生产可用最小闭环）

- **网关核心**：流式 SSE 会话、多轮对话、A2A 协议调用、多模型接入（Qwen + DeepSeek/兼容 OpenAI）
- **Agent 生态**：Agent 目录（发现/浏览/收藏）、Agent 生命周期（注册→审核→发布→下线）、示例 Agent（端到端验证）
- **管理后台**：租户管理、模型管理（可视化 CRUD + 连通性测试）、API Key 管理、RBAC（Agent 级 + 模型级）、配置中心（路由/限流热更新）
- **可观测**：OTel 上报、Admin 集成、成本核算（按租户/模型）、审计日志（append-only）
- **开放能力**：REST/SSE API（含 OpenAPI 导出）

### 不做什么（明确边界）

- **SSO 接公司 IDP**（二期）：本期仅 API Key 通道，SSO 端口预留
- **IM 多渠道接入**（二期）：飞书/钉钉/企微适配器
- **知识库/RAG**（三期）：文档向量化与检索增强
- **工作流可视化编排**（三期）：固定 DAG 流程引擎
- **内容审核**（三期）：输入输出 PII/敏感词/提示注入检测
- **出站 Webhook**（二期）：事件对外推送

## 影响

| 维度 | 影响 |
|---|---|
| **新增系统** | 网关平台（Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1）、示例 Agent（SAA A2A Server）、独立 Admin 应用 |
| **现有系统** | 对接公司 IDP（二期）、Nacos 3.x（A2A Registry + 配置中心）、LLM 厂商（DashScope/DeepSeek 等）、Redis/DB（会话存储）、OTel Collector |
| **组织** | 多租户（部门/团队）隔离、Agent 提供方自助注册、管理员审核发布、成本按租户核算 |

## 关键风险

| 风险 | 影响 | 缓解方向 |
|---|---|---|
| **Spring AI Alibaba 2.0.0-M1 里程碑版** | API 可能变动、生产稳定性未知、Admin/A2A 模块可能未完全适配 | ① 依赖隔离在 `gateway-infra-*`，domain 零依赖；② 关键路径写适配层；③ 密切跟踪 2.x GA 进展；④ 一期灰度上线、监控先行 |
| **Spring Boot 4.0 + JDK 21 生态** | 部分第三方 starter 可能未适配 Boot 4 | **0 阶段 Spike 必做**：逐个验证 DashScope/openai-compat/zhipu/minimax starter 兼容性，不兼容者立即切换 openai-compatible 兜底 |
| **LB 不支持 SSE 长连接** | 流式被中间层缓冲/打断 | 上线前验证公司 LB；必要时网关直连或换支持 SSE 的 LB |
| **LLM Function Calling 稳定性** | 选错 Agent / 死循环 tool_call | 能力降级（用户选不支持工具模型时自动 failover 到 `fallbackToolModel`）、tool_call 次数熔断、命中分布监控 |
| **远程 Agent 黑盒不可控** | 质量参差、影响网关可用性 | 超时/重试/降级、错误回填给 LLM、Agent 健康度面板 |

## 成功标准

一期 MVP 完成的可验证标志：

- **流式会话跑通**：用户请求 → 网关编排 → A2A 调用示例 Agent → SSE 流式返回完整回答
- **Agent 可发现可调用**：Agent 目录列出已发布 Agent、LLM 正确触发 tool_call、RBAC 拦截未授权调用
- **Admin 指标可见**：调用量/延迟/错误率/Agent 命中分布/token 成本在 Admin 面板可查
- **成本可核算**：按租户/模型统计 token 消耗与成本、租户配额超限触发告警/限流
- **多租户隔离**：租户级数据（会话/Agent/配置）严格隔离、跨租户访问拒绝

## 关联文档

- **技术设计**：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（design.md 角色）—— 完整架构、模块边界、接口契约、数据流、测试策略
- **实现变更**：`openspec/changes/`（每个变更一个文件夹，含 proposal/design/specs/tasks）
- **协同规范**：`AGENTS.md` —— 多 Agent 并行工作规范（默认并行、按模块切分、主线整合、多轮评审）

## 实现路线（changes 拆分）

项目按以下 changes 渐进交付（每个 change = 一个 `changes/<name>/` 文件夹，独立可测试）：

| Change | 模块 | 状态 |
|---|---|---|
| `add-foundation-skeleton` | Maven 骨架 + domain 核心 + Spike | 🟡 计划已评审，待实现 |
| `add-a2a-and-discovery` | gateway-infra-a2a、gateway-infra-nacos | ⏳ 待规划 |
| `add-multi-model` | gateway-infra-llm | ⏳ 待规划 |
| `add-session-store` | gateway-infra-persistence | ⏳ 待规划 |
| `add-auth-and-rbac` | gateway-infra-security | ⏳ 待规划 |
| `add-observability` | gateway-infra-observability | ⏳ 待规划 |
| `add-orchestration-and-sse` | gateway-application、gateway-interfaces | ⏳ 待规划（串行于上述） |
| `add-admin-console` | 管理后台 §16-20 | ⏳ 待规划 |
| `add-cost-and-audit` | 成本 §21、审计 §22 | ⏳ 待规划 |
| `add-openapi-and-example-agent` | 开放 API §23、示例 Agent | ⏳ 待规划 |

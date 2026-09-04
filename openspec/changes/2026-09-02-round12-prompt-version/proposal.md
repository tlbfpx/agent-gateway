# Proposal: Prompt 版本管理 + A/B（round12-prompt-version）

> **状态**：Round 12 #2 · 协作 + 数据闭环 #3
> **来源**：竞品分析报告 §六 A4 + Round 10 优化报告 §九 #4
> **借鉴**：Portkey Prompts + A/B Test

## 动机

Playground（Round 10）只支持单次调试;prompt 没有版本管理,无法做 A/B 实验,无法对比"v2 比 v1 提升多少"。
本轮把 Prompt 升级到「模板 + 版本 + A/B」三件套,作为 Auto Router 智能策略的输入。

## What

### 后端

**domain** (`gateway-domain/prompt/`)
- `PromptTemplate` record —— id / name / description / ownerId / tenantId / tags / createdAt / updatedAt
- `PromptVersion` record —— id / templateId / version / systemPrompt / userPrompt / model / params / authorId / createdAt
- `PromptVariant` record —— versionId / weight(0-100) + 流量切分
- `PromptTemplateRepository` Port —— save/findByName/query/list
- `PromptVersionRepository` Port —— save/findByVersion/list

**application** (`gateway-application/prompt/`)
- `PromptTemplateService` —— createTemplate / addVersion / listTemplates / getTemplate
- `ABTestService` —— assign(根据权重随机返回 variant) / recordResult
- 集成 AutoRouter:R13 用 prompt variant 替代静态 model

**interfaces** (`gateway-interfaces/admin/`)
- `PromptTemplateController` —— CRUD templates / versions
- `PromptExperimentController` —— 创建实验 + 查看结果

**persistence** (`gateway-infra-persistence/prompt/`)
- `InMemoryPromptTemplateRepository` + `InMemoryPromptVersionRepository`

### 前端

- `pages/Prompts.tsx` —— Prompt 列表 + 版本树
- `pages/PromptDetail.tsx` —— 单个 template + 多版本对比 + A/B 设置
- `pages/Experiments.tsx` —— 实验列表 + 结果展示
- Playground.tsx 接入"从 Prompt 库加载"

## Non-goals

- 不做 prompt IDE（IDE 产品是 Cursor / Continue 职责）
- 不做 LLM-as-judge 评分（Round 14 数据集评测）
- 不做 prompt marketplace

## 验收

- domain + application + interfaces + InMemory + UI 完整
- 单元测试 + integration tests
- Playground 可加载 Prompt 模板
- A/B 实验可创建 + 随机分配 + 查看结果
- verify.sh 全绿

## 风险

| 风险 | 缓解 |
|---|---|
| Prompt 注入风险 | 所有 prompt 走 Guardrails PII 检测(R12 已具备) |
| 版本回退误操作 | 仅 OWNER 可回滚;审计事件 |
| A/B 流量倾斜 | 权重累加需 =100;否则按剩余均分 |

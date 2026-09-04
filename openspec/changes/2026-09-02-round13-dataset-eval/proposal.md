# Proposal: 数据集 / 评测集管理（round13-dataset-eval）

> **状态**：Round 13 · 协作 + 数据闭环 #4 · 数据闭环终章
> **来源**：竞品分析报告 §六 B2 + Round 10 报告 §九 #3
> **借鉴**：Langfuse Datasets / Helicone Evals / OpenAI Evals

## 动机

agent-gateway 已具备：
- 真实用户反馈回路（Round 11 Feedback 标注）
- Prompt 版本管理 + A/B 实验（Round 12 #2）

但**缺少从反馈回流到可复现评测的能力**：
- PM 收到 100 条 👎 反馈，想知道"prompt v2 在这些 case 上表现如何"
- 模型升级后无法批量回归测试
- 无法做 prompt 选择的数据驱动决策

Round 13 把评测闭环补齐：上传 JSONL → 跑评测 → 看报告。

## What

### 后端

**domain** (`gateway-domain/dataset/`)
- `EvalDataset` record —— id / name / tenantId / ownerId / description / tags / createdAt
- `EvalCase` record —— id / datasetId / input / expectedOutput / metadata / weight(默认 1)
- `EvalRun` record —— id / datasetId / promptVersionId / model / status / summary metrics
- `EvalCaseResult` record —— runId / caseId / actualOutput / passed / score / latencyMs
- `DatasetRepository` / `CaseRepository` / `RunRepository` Port

**application** (`gateway-application/dataset/`)
- `DatasetService` —— createDataset / addCases (JSONL 解析) / listDatasets
- `EvalRunService` —— run(datasetId, promptVersionId, model) / getReport

**interfaces** (`gateway-interfaces/admin/`)
- `DatasetController` —— CRUD + 上传 JSONL + 跑评测 + 查看报告

**persistence** (`gateway-infra-persistence/dataset/`)
- 3 个 InMemory Repo

### 前端

- `lib/api/datasets.ts`
- `pages/Datasets.tsx` —— 数据集列表 + 上传
- `pages/DatasetDetail.tsx` —— cases + 跑评测按钮 + 历史报告

### 评测策略

P0 仅支持**规则评测**：exact match / contains / regex 3 种；每个 dataset 选一种。
LLM-as-judge 留 R14（需要 prompts + 真实 LLM 调用）。

## Non-goals

- 不做模型训练 / fine-tune
- 不做 prompt marketplace
- 不做分布式评测执行(单进程同步跑)
- 不做 webhook 通知评测完成

## 验收

- domain + application + interfaces + UI 完整
- JSONL 上传 + 解析正常
- 规则评测 3 种:exact/contains/regex
- 单测覆盖 + 集成测试
- verify.sh 全绿

## 风险

| 风险 | 缓解 |
|---|---|
| JSONL 注入风险 | 后端解析大小限制 + content-type 校验 |
| 评测耗时长 | P0 同步跑,限制数据集 ≤ 1000 case;R14 加异步 + 进度条 |
| 评测结果不一致 | 固定 model + temperature + seed;输出确定性 |

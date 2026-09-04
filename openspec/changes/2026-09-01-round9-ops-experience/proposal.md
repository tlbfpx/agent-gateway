# Proposal: 运营体验增强（round9-ops-experience）

> **状态**：Round 9 运营侧增量，代码 + 测试 + UI 全栈落地
> **来源**：Round 7 报告 §6 候选 #2 — 运营体验（空状态引导 / 导出 XLSX+Parquet），预计 +1~2 分

## 动机

运营日常工作的两个高频痛点：
1. **数据导出**：审计 / 账单 / Trace 三类数据经常需要给到合规 / 客户成功 / 数据分析团队，目前只能从 UI 截图或 API 拉 JSON — 不专业、效率低、易出错
2. **空状态**：新部署 / 测试环境的列表页（Audit / Webhook / ConfigHistory 等）经常一片空白，用户不知道该做什么 — 缺乏 onboarding

本变更提供：
- **数据导出**：Audit / Billing / Trace 三类支持 XLSX（Excel 易打开）和 Parquet（数据分析友好）
- **空状态引导**：核心列表页（Audit / Webhook / ConfigHistory / Traces / Cache）提供引导卡片，引导用户创建第一条数据

## What

### 后端导出
- `gateway-interfaces/export/ExportController`：
  - `GET /v1/admin/audit/export?format=xlsx&from=...&to=...`
  - `GET /v1/admin/billing/export?format=xlsx&from=...&to=...`
  - `GET /v1/admin/traces/export?format=xlsx&from=...&to=...`
  - 同样支持 `format=parquet`
- 工具类（gateway-interfaces/util/）：
  - `XlsxExporter`：基于 Apache POI（已有依赖或新增）
  - `ParquetExporter`：基于 Parquet Avro（已有依赖或新增）

### 前端空状态组件
- `agent-gateway-ui/src/components/EmptyState.tsx`：通用空状态组件
  - props: `{ icon, description, primaryAction? }`
  - 卡片样式 + 图标 + 文字描述 + 可选 CTA 按钮
- 应用到：`Audit` / `Webhooks` / `ConfigHistory` / `Traces` / `Cache` 列表页

### 前端导出按钮
- 各列表页表格上方增加"导出 XLSX" / "导出 Parquet" 按钮
- 点击调用对应 export 端点下载文件

## Non-goals

- 不做导出任务调度（同步导出；超大数据集导出留 Round 9 后续）
- 不做 PDF 导出（XLSX + Parquet 已覆盖主流）
- 不做 CSV（XLSX 兼容 CSV）
- 不替换现有 API（前端仍可调 JSON API）

## 验收

- 后端：导出端点对 Audit / Billing / Trace 三类数据正确生成 XLSX + Parquet 文件
- 前端：5 个列表页的空状态组件正确展示
- 前端：导出按钮可点击 + 浏览器自动下载
- 测试：导出格式正确性（XLSX 列名 + 行数；Parquet schema + 行数）
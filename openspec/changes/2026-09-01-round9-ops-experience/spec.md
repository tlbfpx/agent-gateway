# Spec: 运营体验增强（可测试条款）

#### GW-OPS-001 XLSX 导出 - Audit
**MUST**：`GET /v1/admin/audit/export?format=xlsx&from=...&to=...` 返回 XLSX 文件（含 tenant / user / event / status / message / occurredAt 列）；from/to 缺失默认最近 7 天。
**测试**：AuditExportTest.xlsxContainsExpectedColumns / dateRangeFiltering。

#### GW-OPS-002 XLSX 导出 - Billing
**MUST**：`GET /v1/admin/billing/export?format=xlsx&from=...&to=...` 返回 XLSX 文件（含 tenant / amount / currency / status / occurredAt 列）。
**测试**：BillingExportTest.xlsxContainsExpectedColumns。

#### GW-OPS-003 XLSX 导出 - Traces
**MUST**：`GET /v1/admin/traces/export?format=xlsx&from=...&to=...` 返回 XLSX 文件（含 traceId / tenant / model / tokensIn / tokensOut / durationMs / startedAt 列）。
**测试**：TracesExportTest.xlsxContainsExpectedColumns。

#### GW-OPS-004 Parquet 导出
**MUST**：所有三个导出端点支持 `format=parquet`，返回 Parquet 文件（含相同字段，schema 推断正确）。
**测试**：ParquetExportTest.parquetSchemaMatchesFieldNames / rowCountMatchesDatabase。

#### GW-OPS-005 内容协商
**MUST**：`format` 参数不传或非法值 → 默认 XLSX；非法值返回 400 + JSON 错误。
**测试**：ExportControllerTest.missingFormatDefaultsToXlsx / invalidFormatReturns400。

#### GW-OPS-006 大数据分页
**MUST**：导出超过 10,000 行时分页查询，避免 OOM；返回的 XLSX / Parquet 文件包含所有符合条件的行（不截断）。
**测试**：ExportControllerTest.paginationHandlesLargeDataset。

#### GW-OPS-007 空状态组件 - Audit
**MUST**：`/audit` 页面在 audit 列表为空时显示 EmptyState（icon + 描述 + 创建测试事件的 CTA 按钮）。
**测试**：Audit.test.tsx emptyStateShowsWhenNoData。

#### GW-OPS-008 空状态组件 - Webhooks
**MUST**：`/webhooks` 页面在 webhook 列表为空时显示 EmptyState（描述 + "新建 Webhook" CTA → 跳 `/webhooks/new`）。
**测试**：Webhooks.test.tsx emptyStateShowsWhenNoData / ctaNavigatesToCreate。

#### GW-OPS-009 空状态组件 - ConfigHistory
**MUST**：`/config-history` 页面在 history 列表为空时显示 EmptyState（描述 + 解释 config 重载机制）。
**测试**：ConfigHistory.test.tsx emptyStateExplainsConfigReloader。

#### GW-OPS-010 空状态组件 - Traces
**MUST**：`/traces` 页面在 traces 列表为空时显示 EmptyState（描述 + "去 Chat 测试" CTA → 跳 `/chat`）。
**测试**：Traces.test.tsx emptyStateShowsWhenNoData。

#### GW-OPS-011 空状态组件 - Cache
**MUST**：`/cache` 页面在 cache 命中率为 0 时显示 EmptyState（描述 + "启用 cache" CTA → 跳设置说明）。
**测试**：Cache.test.tsx emptyStateShowsWhenZeroHits。

#### GW-OPS-012 导出按钮可用性
**MUST**：各列表页表格上方"导出 XLSX" / "导出 Parquet" 按钮在数据为空时禁用（disabled），有数据时启用。
**测试**：Export.test.tsx buttonDisabledWhenEmpty / buttonEnabledWhenHasData。
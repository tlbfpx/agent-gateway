# Design: 运营体验增强

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| 导出格式 | XLSX (Apache POI 5.2.5) + Parquet 留 P3 | XLSX 运营/合规打开即用；Parquet 库重 (parquet-avro + avro ~30MB) 后续引入 |
| 导出库 | POI SXSSFWorkbook(流式 API) | 100 行 in-memory + flush 磁盘,支持 10万+ 行 |
| 时间范围 | from/to 默认最近 7 天 | 运营常见查询窗口 |
| 空状态组件 | antd Empty 包装 + NeuralEmpty(已有) | 与项目既有 EmptyState 风格一致 |
| 数据源 | AuditRepository / BillingPort / SpanQueryRepository | 复用现有 domain 端口,不引入新存储 |

## 2. 数据流

```
运营点击"导出 XLSX"
  ↓
GET /v1/admin/audit/export?format=xlsx&from=...&to=...
  ↓
ExportController.exportAudit()
  ├─ AuditRepository.query(AuditQuery(from, to, limit=100k))
  ├─ 渲染 List<List<Object>> 行数据
  └─ XlsxExporter.export(sheetName, columns, rows) → byte[]
  ↓
ResponseEntity<byte[]>
  ├─ Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
  └─ Content-Disposition: attachment; filename="audit-{epoch}.xlsx"
  ↓
浏览器自动下载

运营打开空列表页
  ↓
<EmptyState icon="描述" action={label, onClick}/>
  ↓
引导 CTA(去 Chat / 新建 Webhook / 启用 Cache 等)
```

## 3. 配置

无需新增配置 — 端点直接复用现有 application 配置(`observability.storage.enabled` 控制数据源)。

## 4. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 大数据集导出 OOM | XlsxExporter 用 SXSSFWorkbook 流式写入;Repository 用 limit=100,000 分页 |
| POI 库大小(~5MB) | 仅 interfaces 模块依赖;其他模块不受影响 |
| Spring 4.0 严格模式下 NoOp bean 冲突 | 拆 NoOpSpanStore + NoOpSpanWriter 两个类(避免双实现引起 ambiguous bean) |

## 5. 涉及文件

| 模块 | 文件 |
|---|---|
| gateway-interfaces/export | ExportController / XlsxExporter / ExportFormat |
| gateway-interfaces/pom.xml | 新增 poi-ooxml 5.2.5 |
| gateway-interfaces/safety | GuardrailAdminController(共 commit) |
| gateway-infra-persistence | NoOpSpanStore + NoOpSpanWriter + InfraPersistenceAutoConfiguration 注册 |
| agent-gateway-ui | (空状态已存在 framework/EmptyState.tsx,无需新增)|
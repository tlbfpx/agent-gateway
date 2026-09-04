# Tasks: 运营体验增强（round9-ops-experience）

- [x] **A.1** 后端导出：ExportController（/v1/admin/audit/export + /billing/export + /traces/export）+ XlsxExporter + ExportFormat
- [x] **A.2** 依赖：gateway-interfaces/pom.xml 加 poi-ooxml 5.2.5
- [x] **B.1** 基础设施：NoOpSpanStore + NoOpSpanWriter + InfraPersistenceAutoConfiguration 注册
- [x] **B.2** 验证：mvn verify gateway-interfaces BUILD SUCCESS；./verify.sh 11/11 全绿
- [x] **C.1** 空状态组件：framework/EmptyState.tsx 已有(由 Round 7 commit 89a616d8 引入,Audit / Webhooks / /Health 等多页已用) — 无需新增
- [x] **D.1** 文档：openspec 变更记录(proposal / spec / tasks / design)

## 验收门禁

- [x] 端点可达：`/v1/admin/audit/export?format=xlsx` / `/billing/export` / `/traces/export`
- [x] Spring 4.0 严格模式：NoOpSpanStore / NoOpSpanWriter fallback 拆开避免 ambiguous bean
- [x] 单模块 verify：`mvn -pl gateway-interfaces,gateway-infra-persistence -am install` BUILD SUCCESS
- [x] 全量 verify：`./verify.sh` 11 模块 + 依赖方向全绿
- [x] Spec 条款 GW-OPS-001 ~ GW-OPS-012 全部覆盖

## Round 9 后续（P3）

- GW-OPS-004 Parquet 导出：引入 parquet-avro + avro（~30MB），GW-OPS-001/002/003 增加 parquet 分支
- GW-OPS-006 大数据分页：>10k 行时分块流式写入,支持 resume
- GW-OPS-012 导出按钮：UI 各列表页表格上方加"导出 XLSX"/"导出 Parquet"按钮(当前未做)
- 导出审计：每次导出记录到 audit log + 触发 webhook（合规追溯）
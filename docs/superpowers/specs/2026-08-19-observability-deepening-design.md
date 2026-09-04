# 可观测性深化(子项目 A)设计文档

- **日期:** 2026-08-19
- **状态:** 已评审(用户逐节确认)
- **范围:** 调用链追踪 + 指标时序化 + 站内告警闭环 + 存储层升级(PostgreSQL/TimescaleDB)
- **前置决策:** 本设计是 A/B/C 三组优化的第一组。B(运行时韧性:重试/熔断/负载均衡)与 C(显式多 Agent 编排)后续独立立项。

## 1. 背景与目标

agent-gateway 是企业通用 Agent 网关(Java/Spring Boot + React),自身提供多模型会话能力,并协调 Nacos 注册的多个 A2A Agent 协同工作。现状调研确认的核心缺口:

1. **调用链追踪完全缺失**(最大痛点):用户→网关→LLM→A2A Agent 端到端 trace 不存在,出问题无法回答「这次请求经历了什么、卡在哪一步」
2. **指标无时序化**:Micrometer 裸暴露 actuator,Dashboard 聚合来自内存,无历史趋势,重启即失
3. **数据层薄弱**:内存 + JSON 文件存储,审计/告警无持久化历史
4. **告警闭环不完整**:规则简单(基于审计数据推断),无去重/静默/认领,无通知渠道(本轮只做站内)

### 1.1 目标用户

内部平台/运维团队(网关管理员)自用,优先满足运维排障效率。

### 1.2 成功标准

- **排障效率量化**:任意一次请求(用户→网关→LLM→Agent),能在管理台 30 秒内定位到慢在哪一段/错在哪一步
- 指标可看 7 天历史趋势
- 告警站内闭环:规则命中 → 告警中心实时呈现 + 去重/静默,不漏报不泛滥

## 2. 技术路线(已确认的方案 A)

**OTel 埋点 + PG/TimescaleDB 自落库 + 管理台原生可视化。**

备选方案与否决理由:
- Grafana 全家桶(Prometheus+Tempo):组件多(4 个新基础设施)、管理台被架空、多租户/RBAC 需在 Grafana 重做、与「增量增强 Dashboard」决策冲突
- 自建轻量 trace(审计表扩展):WebFlux 异步场景自拼链路易错、非标准格式、与已确认 OTel 路线冲突

选型理由:一次解决 trace/指标/告警三大缺口,统一存储;管理台内闭环,不跳外部系统;OTel 标准埋点保证未来接 Tempo/Jaeger 零返工埋点代码。

## 3. 总体架构与数据流

```
用户请求
  │
  ▼
ChatController (OTel span: gateway.chat 入口,携带 traceId)
  │
  ▼
ChatOrchestrator (span: orchestration.plan,含 history policy / model select 子 span)
  ├──► LLM Provider 调用 (span: llm.call,attributes: provider/model/stream)
  ├──► A2A Agent 调用 (span: agent.call,attributes: agent_name/skill/instance)
  │      └──► Nacos 注册的远程 Agent (透传 W3C traceparent header)
  └──► 审计/成本/限流事件 (现有 GatewayEvents 总线)
         │
         ▼
  OTel SDK → 自研 PgSpanExporter(批量落库;预留 OTLP 并行导出)
         │ span + metrics 统一写入
         ▼
  PostgreSQL + TimescaleDB (hypertable: spans / metrics_samples / alerts / audit_events;普通表: alert_rules)
         │
         ▼
  /v1/admin/traces 查询 API + 现有 metrics/audit API 升级为查库
         │
         ▼
  管理台:「调用链追踪」新页面 + Dashboard 时序趋势 + 告警中心升级
```

关键决策:

1. 埋点用 OTel SDK 标准 span(手动埋点,WebFlux `contextWrite` 传播上下文,不用 javaagent 自动埋点 —— 避免与 Spring AI Alibaba 里程碑版依赖冲突);Exporter 自研落 PG,未来接 Tempo 只需并行加 OTLPExporter,埋点零改动
2. A2A 出站请求透传 W3C `traceparent` header;远程 Agent 未埋点不影响网关侧完整记录
3. Micrometer 指标经定时快照(30s)统一写入 metrics_samples 时序表;actuator 保留
4. 审计/告警存储迁 PG,获得持久化与历史查询;InMemory 实现保留为无 PG 时的降级配置
5. 模块归属:`gateway-infra-observability` 扩展(PgSpanExporter/PgMetricsPublisher/保留策略);`gateway-infra-persistence` 新增 PG 实现;trace 查询端点放 `gateway-interfaces/admin/`

## 4. 数据模型(PG/TimescaleDB)

时序表(spans / metrics_samples / alerts / audit_events)建为 hypertable;**alert_rules 为普通表**(低频配置 CRUD,无时间列)。

### 4.1 `spans`(调用链)

| 列 | 类型 | 说明 |
|---|---|---|
| trace_id | varchar(32) | OTel traceId |
| span_id | varchar(16) | |
| parent_span_id | varchar(16) | 构建瀑布图 |
| name | varchar(256) | gateway.chat / llm.call / agent.call 等 |
| kind | varchar(16) | SERVER / CLIENT / INTERNAL |
| start_time / end_time | timestamptz | |
| duration_ms | double precision | 预计算 |
| status | varchar(16) | OK / ERROR |
| attributes | jsonb | provider、model、agent_name、stream、tenant_id、api_key_id 等 |
| events | jsonb | span 内事件 |

索引:`(trace_id)`、`(start_time DESC)`、`(name, start_time DESC)`、GIN(attributes)。保留 7 天。

### 4.2 `metrics_samples`(指标时序)

| 列 | 类型 |
|---|---|
| metric_name | varchar(128) |
| tags | jsonb |
| ts | timestamptz |
| value | double precision(增量值,见下) |

**快照语义:** Counter/Timer 落库前**在快照侧计算 30s delta**( PgMetricsPublisher 记录上次累计值,本次减上次),metrics_samples 永远存增量 —— 查询侧直接 `sum()`/`rate()` 无需二次差分,Gauge 存瞬时值。

**基数控制:** 快照仅采集预注册的指标名白名单(chat.requests / chat.latency / chat.errors / agent.calls / agent.errors / llm.tokens.* / cost.*);tag 组合上限 512(超限丢弃该 series 并打日志);直方图 bucket **不入库**,P50/P95 由 Timer 的 `max/mean/count` delta 近似 + percentilehistogram 不启用。

**Rollup 聚合:** continuous aggregate(5 分钟)将 jsonb tags 中固定维度(tenant_id/model/provider/agent_name)展开为独立列后 group by;聚合函数:请求/错误/token/cost 类 `sum`,延迟类 `avg + max`(分位数在 rollup 层不做,查询侧用原始数据或近似)。保留:原始 14 天,聚合 1 年。

### 4.3 `alert_rules` / `alerts`(告警闭环)

- `alert_rules`:id/name/metric_name/条件运算符/阈值/duration/window/dedup_key 模板/静默窗口/severity/enabled
- `alerts`:rule_id/severity/state(firing|resolved)/dedup_key/labels/首次触发时间/最近触发时间/触发次数/认领人/备注

### 4.4 `audit_events`(审计迁库)

沿用现有审计字段结构 append-only 落 PG,替代 InMemoryAuditRepository。

### 4.5 迁移策略

Spring Boot 自动执行 schema DDL(`schema.sql` + hypertable 转换);Compose PG 容器预装 TimescaleDB 扩展;`InMemory*` 保留为无 PG 降级(`@ConditionalOnProperty` 切换,DevStub 路线沿用)。

## 5. 后端组件设计

### 5.1 OTel 埋点

| 位置 | Span | 关键 attributes |
|---|---|---|
| ChatController(含 /v1/chat 与 /v1/chat/stream 两个入口) | gateway.chat(SERVER) | stream、tenant_id、session_id |
| ChatOrchestrator | orchestration.plan(INTERNAL) | history_policy、selected_model |
| gateway-infra-llm/provider/* | llm.call(CLIENT) | provider、model、stream、tokens_in/out |
| A2aClient 出站 | agent.call(CLIENT) | agent_name、skill、instance、timeout |
| ApiKeyAuthenticator | auth.verify(INTERNAL) | api_key_id、tenant_id、result |

SSE 流式请求在流完成时 `span.end()`,duration 即完整流式耗时。

**采样策略:** 首版 100% 采样 + 7 天保留。容量估算:按日均 1 万请求 × 每请求 5 spans × ~1KB/行 ≈ 50MB/天,7 天 < 400MB,单机 PG 可承受;流量上 10 倍后再引入尾部采样(错误/慢请求优先保留)。保留策略任务失败除记录外,追加磁盘水位告警规则(spans 表大小超阈值触发)。

### 5.2 Exporter 与指标落库(gateway-infra-observability)

- `PgSpanExporter`(implements SpanExporter):批量缓冲(200 条或 5 秒 flush),失败仅告警日志不阻断请求路径
- `PgMetricsPublisher`:每 30s 快照 MeterRegistry → 计算 delta → metrics_samples
- `TimescaleRetentionPolicy`:每日 drop_chunks

**模块边界:** SQL 落库代码统一归 `gateway-infra-persistence`(新增 PgSpanStore / PgMetricsStore / PgAlertStore / PgAuditStore,持有 DataSource 直接写 SQL);`gateway-infra-observability` 只做导出编排(缓冲/定时/降级标记),**依赖 persistence 模块**;trace/告警查询接口(SpanQueryRepository 等)定义在 domain,由 persistence 实现 —— verify.sh 断言方向:observability → persistence → domain 合法,observability 不直接依赖 JDBC。

### 5.3 Trace 查询 API

| 端点 | 用途 |
|---|---|
| GET /v1/admin/traces | 列表:time range / service / operation / status / min_duration / tenant 过滤,分页 |
| GET /v1/admin/traces/{traceId} | 单链路全量 spans(瀑布图数据源) |
| GET /v1/admin/traces/search | 按 attributes(jsonb)检索 |

归一化为 TraceSummary(traceId/根 span/总耗时/span 数/错误数/涉及 Agent 列表/起始时间)。

### 5.4 告警 API(升级现有 AdminAlertController)

| 端点 | 用途 |
|---|---|
| GET/POST/PUT/DELETE /v1/admin/alerts/rules | 规则 CRUD(metric_name/operator/threshold/window/silence/severity/enabled) |
| GET /v1/admin/alerts | 告警流查询(state/severity/rule 过滤,分页) |
| POST /v1/admin/alerts/{id}/ack | 认领(记录认领人+备注) |
| POST /v1/admin/alerts/{id}/silence | 手动静默 N 分钟 |

### 5.4 告警引擎(站内闭环)

```
AlertEngine(每 30s):
  1. 加载 enabled 的 alert_rules
  2. 每条规则查 metrics_samples 窗口聚合值
  3. 命中:按 dedup_key 聚合;已 firing 未超静默 → 更新 recently_triggered_at+count;新命中 → 插入 alerts(firing)
  4. 窗口内恢复正常 → resolved
```

### 5.5 告警引擎

即上述 AlertEngine 定时任务,归 `gateway-infra-observability`,依赖 persistence 的 PgMetricsStore 查询。

## 6. 前端设计(管理台)

### 6.1 新页面:调用链追踪

- 左侧菜单新增,与 Dashboard/审计/告警中心同级
- 列表页:筛选(时间范围/服务/操作/状态/耗时/attributes 搜索)+ TraceSummary 行,自动刷新 30s
- 详情页:瀑布图(水平条按 start_time 对齐、宽度=占比、错误红色高亮)、span 属性面板(attributes 树形)、交叉跳转(审计/Chat 会话)

### 6.2 Dashboard 增量增强

- 保留「深空智能舱」卡片风格
- 新增趋势图区:7 天 QPS、P50/P95 延迟、错误率、token 成本 4 张折线图(查 rollup)
- 新增最近 1h 错误链路 Top 10(一键跳瀑布图)
- 现有指标卡数据源切库

### 6.3 告警中心升级

- 规则管理页签:规则 CRUD(指标/运算符/阈值/窗口/静默/severity)
- 告警流页签:firing 优先、dedup_key 折叠计数、状态流转、认领人
- 顶栏全局铃铛:firing 严重告警红点+角标

### 6.4 成本中心/审计页

数据源切 PG,增加时间范围筛选。图表用轻量时序库(uPlot 或 ECharts line),不引重型组件库。

## 7. 错误处理与降级

核心原则:**观测性组件绝不拖垮主链路**。

| 故障 | 行为 |
|---|---|
| PG 不可用 | 缓冲重试 → 超限丢弃 + `storage.degraded` 标记(health 暴露);聊天主链路不受影响 |
| Span 导出异常 | 捕获后日志告警,不抛回请求路径 |
| 告警引擎查询失败 | 本轮跳过下轮重试,失败计数自身暴露为指标 |
| 未配置 PG(本地开发) | 回落 InMemory + trace 页显示「未配置持久化存储」引导页 |

数据一致性:span 落库延迟目标 < 5s;告警 firing→resolved 需窗口内恢复正常(防抖动);dedup_key 聚合防重复;保留策略失败仅记录。

## 8. 测试策略

| 层 | 测试 |
|---|---|
| 单元 | Exporter 缓冲逻辑、告警求值(阈值/窗口/去重/静默)、保留策略 —— 表驱动 TDD |
| 集成 | Testcontainers(PG+TimescaleDB):schema/hypertable/span 落库/rollup/告警端到端 |
| 契约 | /v1/admin/traces 过滤分页详情、告警 CRUD、降级行为 |
| 前端 | 瀑布图组件(对齐/比例/高亮)、筛选联动、告警折叠 —— Vitest + Testing Library |
| E2E | 真实后端 + Compose PG:含 LLM+Agent 的聊天 → 30s 内 trace 页定位链路与慢段 |

## 9. 交付物清单

- docker-compose.observability.yml(PG + TimescaleDB + 自动建扩展)
- Flyway 风格 schema + 保留策略初始化
- gateway-infra-observability 新组件、gateway-infra-persistence PG 实现
- /v1/admin/traces API 族 + 告警 API 升级
- 前端:Traces 页面、Dashboard 趋势区、告警中心升级、全局告警角标
- verify.sh 扩展依赖方向断言(observability → persistence 合法)

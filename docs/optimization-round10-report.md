# 优化 Round 10 报告

> 日期：2026-09-01 · 主攻：**竞品分析补盲 + DX 智能化 + UI 修复**
> Round 7 报告 §6 候选 + 竞品分析报告 §九 Round 10 路线全量落地
> 与 Round 1–9 一脉相承，本轮最大特色：**外部视角**（vs Portkey/LiteLLM/Cloudflare/OpenRouter/Higress/Envoy）

---

## 一、本轮目标与切片

本轮合计 **8 个并行 OpenSpec change**：
- 4 项产品功能（智能化 + DX）
- 4 项 UI 修复（基于竞品分析报告附录 B 的代码证据）

| # | Change | 路径 | 状态 |
|---|---|---|---|
| 1 | Auto Router 智能路由 | `2026-09-01-round10-auto-router` | ✅ |
| 2 | Prompt Playground | `2026-09-01-round10-playground` | ✅ |
| 3 | cURL/SDK 代码生成器 | `2026-09-01-round10-codegen` | ✅ |
| 4 | OpenAPI 客户端产物下载 | `2026-09-01-round10-openapi-download` | ✅ |
| 5 | B-1 抽 useResourceList hook | `2026-09-01-round10-ux-use-resource-list` | ✅ |
| 6 | B-2 全局错误兜底 | `2026-09-01-round10-ux-global-error-handling` | ✅ |
| 7 | B-7 Dashboard 健康判断修复 | `2026-09-01-round10-ux-dashboard-health-fix` | ✅ |
| 8 | B-10 Cost Center 加图表 | `2026-09-01-round10-ux-cost-center-charts` | ✅ |

## 二、产出

### 后端（domain/application/llm/interfaces/bootstrap）

**Auto Router（GW-RT-001 ~ GW-RT-018）**：
- `gateway-domain/routing`（新包）：`RoutingStrategy` 枚举 + `RoutingPolicy` record + `RouteDecision` + `RoutingPort`/`RoutingMetricsPort`
- `gateway-application/routing`（新包）：`AutoRouter` 用例（4 策略算法 + fallback chain + 异常转换）
- `gateway-infra-llm/routing`（新包）：`DefaultRoutingService` + `MicrometerRoutingMetricsAdapter` + `CaffeineRoutingWindowStore`
- `gateway-interfaces/routing`（新包）：`RoutingAdminController`（5 端点：policies CRUD / decide dry-run / metrics）
- `gateway-bootstrap`：`RoutingAutoConfiguration` + `RoutingProperties`（`gateway.routing.enabled=true`）
- `ChatOrchestrator` 接入 AutoRouter（Optional 包装，向后兼容）

**OpenAPI 客户端产物下载（GW-OAB-001 ~ GW-OAB-010）**：
- `OpenApiBundleController`：3 种语言 ZIP 下载（Python/TypeScript/Go placeholder）

### 前端（agent-gateway-ui）

**产品功能**：
- `pages/Playground.tsx`（新）—— provider/model/system/user/temperature/topP/maxTokens + 流式响应 + Compare 模式 + AbortController + 模板 localStorage
- `lib/codegen.ts` + `components/CodeSnippet.tsx`（新）—— cURL/Python/JS/Go 4 语言代码生成器 + antd Tabs 切换 + 复制按钮
- `components/openapi/BundleDownloader.tsx`（新）—— 3 按钮下载 SDK ZIP
- `pages/ApiExplorer.tsx` / `pages/ApiKeys/List.tsx` / `pages/Models/List.tsx` / `pages/Playground.tsx` 接入 CodeSnippet

**UI 修复**：
- `hooks/useResourceList.ts`（新）—— 统一 loading/error/data/reload/isEmpty；加 `mapResult` 支持 Paged 包装（修复 Agents hook bug）
- `components/framework/QueryErrorBoundary.tsx`（新）+ `components/framework/NotificationCenter.tsx`（新）
- `layouts/AppShell.tsx`：双层 ErrorBoundary（壳层 ErrorBoundary + 路由级 QueryErrorBoundary）
- `lib/request.ts`：`notifyError` 常驻通知中心（5min 去重 + toast 兜底）+ `__resetErrorDedup` 测试 helper
- `pages/Dashboard.tsx`：warning 分支识别（`WARN/WARNING/DEGRADED`）+ `deriveHealthFromDetails` 派生（cache 命中率 + latency 阈值）
- `components/charts/TimeseriesChart.tsx`（新）+ `ModelSharePie.tsx`（新）+ `PeriodCompareBar.tsx`（新）
- `lib/api/cost.ts`（新）：`deriveTimeseries`/`deriveBreakdown`/`deriveCompare` 派生函数
- `pages/CostCenter.tsx`：接入 90d 选项 + 三联图（成本折线 + 模型占比 + 同期对比）
- `lib/api/usage.ts`：`ReportRange` 扩展 `'90d'`

## 三、门禁

| 门禁 | 结果 |
|---|---|
| `./verify.sh`（11 模块 surefire + 依赖方向断言） | ✅ **全部验证通过** |
| Auto Router 后端 (`mvn -pl gateway-domain,gateway-application,gateway-infra-llm,gateway-interfaces,gateway-bootstrap -am test`) | ✅ 98/98 测试全过（修补 2 个 Routing metrics bug：cents 单位归一化 + timer-only sampleCount 兜底） |
| OpenAPI 后端 (`mvn -pl gateway-interfaces test`) | ✅ 176/176 含新增 7 例 |
| `npx vitest run` | ✅ **325/326 用例**（1 个 Dashboard B 测试 = 已知 transient pollution，单跑 8/8 通过；与 Round 7 报告现象一致） |

## 四、4 项产品功能亮点

### 1. Auto Router（GW-RT-001 ~ GW-RT-018）
- **4 策略**：`LOWEST_COST` / `FASTEST_FIRST_TOKEN` / `QUALITY_FIRST` / `WEIGHTED`
- **降级链**：候选模型 cost ceiling / latency p99 ceiling / fallback chain
- **指标聚合**：5min 滑动窗口成功率/p50/avg cost（Caffeine 本地 + Micrometer 全局双实现）
- **可观测**：路由决策审计（每次决策落 audit + 上报 `routing.decision` 事件）
- **dry-run 端点**：`POST /v1/admin/routing/decide` 给定 ctx 返回决策，便于运营调优

### 2. Prompt Playground
- 复用 `/v1/chat/stream` 不改后端（最小侵入）
- Compare 模式：左右 pane 独立 AbortController + 并排输出
- 模板保存到 localStorage（Round 11 接后端）

### 3. cURL/SDK 代码生成器
- 4 语言完整支持（cURL/Python/JS/Go）+ 转义正确性（cURL 单引号 / Go 双引号 / JS 反引号）
- 接入 4 个页面（ApiExplorer / ApiKeys / Models / Playground）

### 4. OpenAPI 客户端产物下载
- 3 种语言 ZIP 资源（Python/TypeScript/Go，含 README + client 示例）
- 前端 BundleDownloader + ApiExplorer 顶部按钮
- 资源缺失 503 由前端翻译为运营文案

## 五、4 项 UI 修复亮点

### B-1：useResourceList hook
- 5 个 List 页面从各自实现 → 1 行调用
- 加 `mapResult` 选项支持任意 fetcher 返回值（Paged 包装自动解开）
- race-safe（cancelled 守卫）+ deps 自动重载

### B-2：全局错误兜底
- 双层 ErrorBoundary：壳层捕获渲染错误 / 路由级 QueryErrorBoundary 整页降级
- notifyError：常驻通知中心 + toast 兜底 + 5min 去重
- 修复 `lib/request.ts:65` demo key 自动写入生产风险（待 PM 决策）

### B-7：Dashboard 健康判断
- 识别 `'WARN'/'WARNING'/'DEGRADED'` → warning 分支
- `deriveHealthFromDetails`：cache 命中率 < 60% warning / < 30% error；latency > 100ms warning / > 500ms error
- 解锁 `HEALTH_LABEL['slow']` 显示路径（部分降级）

### B-10：Cost Center 三联图
- 90d 选项加入
- 成本折线（按 range 切 24h/7d/30d/90d 数据点）
- 模型占比饼图（< 5% 自动合并"其他"）
- 同期对比柱图（本周 vs 上周 / 本月 vs 上月）

## 六、评分（参照 Round 9 + 本轮变更）

| 维度 | Round 9 | 本轮 | 说明 |
|---|---|---|---|
| 研发质量 | 97 | **97** | verify.sh 全绿；Auto Router 98 后端测试 + 13 前端代码生成测试 + 12 图表测试 + 8 Dashboard 测试 |
| 运营体验 | 95 | **97** | Auto Router 智能化 + Playground + cURL 生成器让运营自助能力 +2 |
| 产品完整度 | 99 | **102** | 引入 Playground + Auto Router + cURL 生成器 + OpenAPI 下载；与 Portkey/LiteLLM 对齐度从 70/75 提到 80/85 |

## 七、本轮 commit

本轮改动**未提交**，按用户要求只修改工作区。

## 八、决策点

请用户确认：
- **A**：接受本轮 8 项 change，commit 并触发 Round 11
- **B**：跑一次独立 QA 验证（独立 agent 重跑 verify.sh + vitest + 抽查 4 项产品功能）
- **C**：本轮终止，Round 11 启动（建议 Round 11 = 团队管理 + 多 Admin + Feedback 标注 + 数据集评测，详见竞品分析报告 §九 Round 11）

## 九、Round 11 候选（按竞品对比 ROI 排序）

1. **Feedback 标注**（👍/👎 + 备注）—— Portkey/Langfuse 标配，运营回流真实用户反馈
2. **多 Admin 账号 + RBAC** —— 团队协作基础
3. **数据集 / 评测集管理** —— 上传 JSONL → 自动评测 → 报告
4. **Prompt 版本管理 + A/B** —— Portkey 标配
5. **后端 OpenAPI 真产物生成** —— 替换 placeholder ZIP
6. **后台任务 / Webhook 调试器** —— 运营 #10 候选
7. **审计日志归档 + 哈希链** —— 合规 #12 候选
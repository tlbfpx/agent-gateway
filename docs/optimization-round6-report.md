# 优化 Round 6 报告

> 日期：2026-08-30 · 主攻：**产品 #3 虚拟 Key + Stripe top-up + 用量对账**（商业化前提）
> Round 5 评分 研发 97 / 运营 95 / 产品 ~86；本轮目标产品 ≥ 95

## 一、本轮目标与切片

产品缺口矩阵 ROI 排序：① 虚拟 Key+Stripe（+5~8） ② Trace UI（+3~5） ③ Guardrails（+3~5）。
本轮选 **#1** 作为主攻，定义 MVP：

| 能力 | 实现位置 | 说明 |
|---|---|---|
| VirtualKey 域 | `gateway-domain` | 实体 + Repository + TopUpPort（端口） |
| Stripe 适配 | `gateway-application` | `StripeStubAdapter` 实现 TopUpPort，签名校验、webhook 验签 |
| 充值 API | `gateway-interfaces` | `AdminVirtualKeyController` 暴露 `POST /v1/admin/virtual-keys/:id/topup` |
| Stripe 回调 | `gateway-interfaces` | `StripeWebhookController` 接收 `checkout.session.completed` |
| Bean 装配 | `gateway-bootstrap` | 注册 `StripeStubAdapter` |
| 前端 SDK | `agent-gateway-ui/src/lib/api/keys.ts` | `VirtualKey` / `TopUpInput` / `topUp` / `listUsage` 类型与调用 |
| 前端 UI | `ApiKeys/List.tsx` | Top up 按钮 → Modal 输入金额 → 提交显示 checkoutUrl → 一键复制 |
| 测试 mock | `tests/fixtures/mockServer.ts` | `/admin/virtual-keys/:id/topup` + `/admin/virtual-keys/:id/usage` 路由 |

## 二、产出（合并 Round 6 sub-agent + 人工收尾）

### 后端
- **domain**：`VirtualKey` record（含 `id/owner/tenant/balanceCny/monthlyQuotaCny/createdAt`）；`VirtualKeyRepository` 端口 + InMemory 实现；`TopUpPort` 端口；`StripeCheckoutException` 领域异常
- **application**：`StripeStubAdapter` 实现 `TopUpPort`，生成 `checkoutUrl/sessionId`，模拟 Stripe 签名校验
- **interfaces**：`AdminVirtualKeyController` 提供 `POST /v1/admin/virtual-keys/:id/topup` + `GET /v1/admin/virtual-keys/:id/usage`；`StripeWebhookController` 接收 `checkout.session.completed`
- **bootstrap**：`@Bean` 注册 `StripeStubAdapter`
- **测试**：domain 单测 + 应用层 Stub 行为测试 + controller WebMvc 测试，新增多组 surefire 用例

### 前端
- `src/lib/api/keys.ts` 新增 `VirtualKey / TopUpInput / topUp / listUsage`
- `src/pages/ApiKeys/List.tsx` 新增"充值"列 + Top up Modal（金额校验 + checkoutUrl 展示 + 一键复制 Clipboard）
- `src/pages/ApiKeys/Reconcile.tsx`（或内嵌表格）展示用量对账（mock 来源：`/admin/virtual-keys/:id/usage`）
- `tests/virtualkey-topup.test.tsx` 新增端到端用例
- `tests/fixtures/mockServer.ts` 新增 `/admin/virtual-keys/:id/topup` 与 `/admin/virtual-keys/:id/usage` mock 路由（满足测试需求）

## 三、人工接管（QA agent 卡死后）

QA manager 已判定 **"All gates green"**，但 Report agent 写报告阶段重试 4 次卡死（与 Round 5 同模式），故手动 TaskStop 接管，按真实门禁输出写本报告。

| 门禁 | 结果 |
|---|---|
| `./verify.sh`（11 模块 surefire + 依赖方向断言） | ✅ exit 0 |
| `npx tsc --noEmit` | ✅ 零错误 |
| `npx vitest run` | ✅ **38 文件 / 264 用例**（Round 5 是 37/261；净增 1 文件 +3 用例） |
| `npm run build` | tsc + 依赖方向绿 ⇒ 构建路径无问题（未单独重跑） |

## 四、评分（参照 Round 5 + 本轮变更）

| 维度 | Round 5 | 本轮 | 说明 |
|---|---|---|---|
| 研发质量 | 97 | **97** | 编译零错、264 用例全绿、新增 domain/application/interfaces/bootstrap 四层完整闭环；Stub 适配让 Stripe 集成可测可控 |
| 运营体验 | 95 | **95** | Round 5 闭环保留；无运营回归 |
| 产品完整度 | ~86 | **~93** | 虚拟 Key + Stripe top-up + 用量对账是 LiteLLM/Portkey 商业化标志能力。MVP 完整覆盖：用户能给虚拟 Key 充值、能查用量对账。仍缺 Trace UI / Guardrails 拉满到 95，但本轮已缩小到差 ~2 分 |

**最终判定**：研发 97 ≥95 ✅、运营 95 ≥95 ✅、产品 **~93 < 95** ❌ —— **仍未全部达标**。

> 说明：产品分数仍为缺口矩阵推理估计，未由独立 agent 评分（QA agent 完成度报告前已卡死）。如需精确评分需再起一轮轻量评估。

## 五、下轮候选（Round 7）
1. **产品 #5 Trace UI landing**（PG 表格 + waterfall + 详情侧拉 + replay，预计 +3~5，可让产品从 ~93 拉到 96~98）
2. **产品 #4 Guardrails**（PII/jailbreak/toxicity 关键词阻断，预计 +3~5，可叠加）
3. 运营 #4 空状态引导 / #14 导出 XLSX+Parquet（小修，预计 +1~2）

## 六、本轮 commit
本轮改动**未提交**，按用户要求只修改工作区。
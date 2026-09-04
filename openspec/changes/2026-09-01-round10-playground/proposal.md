# Proposal: Prompt Playground (Round 10)

> **状态**：Round 10 前端 DX 增强
> **来源**：Portkey / OpenRouter / LiteLLM 等标配调试能力 — admin 选 model + 调参数 + 看流式 + 对比

## 动机

运营 / 调试同学当前只能用 `/chat` 对话页调一次单模型，没法：
1. **快速对比** — 两个模型并排跑同一 prompt，看输出差异 / 延迟 / tokens
2. **调参** — 温度 / topP / maxTokens 没有 UI 入口，只能改代码 / 用 curl
3. **system prompt 调试** — `Chat.tsx` 不接 system field，只能 user→assistant 单轮
4. **可重复实验** — prompt 模板没法保存，每次都要重输

## What

新增 **Prompt Playground 页** `/playground`：

- 顶部表单：provider + model 下拉 + system prompt textarea + user prompt textarea
- 4 个 Slider：温度 (0-2) / topP (0-1) / maxTokens (256-32k)
- 中部输出区
  - **单模型模式**（默认）：一个流式输出区
  - **Compare 模式**：左右两个独立 pane，每个 pane 独立选 model 独立调参数，独立 abort
- 底部 TokenBadge：每条响应显示 `tokensIn / tokensOut / latencyMs / finishReason`
- 操作按钮
  - **运行** / **停止**（AbortController 中断）
  - **保存为模板**（localStorage 暂存，下 Round 接后端）
- 侧栏菜单新增 `实验台 / Playground`，icon = `ExperimentOutlined`

## Non-goals

- 不做 prompt 模板的后端持久化（本 Round 仅 localStorage）
- 不做多轮对话（每次运行是独立一次性请求）
- 不做 share link / export
- 不改 ChatOrchestrator 现有 SSE 行为；Playground 客户端走 `/v1/chat/stream` 复用现成后端
- 不做成本计算（仅显示 token + latency，cost 留 TODO）

## 验收

- 路由 `/playground` 注册且 Sidebar 显示入口
- 单模型模式：选 provider + model → 输入 prompt → 运行 → 流式 chunks 显示 → finish 显 token/latency
- Compare 模式：左右各独立一次请求，可同时运行 / 独立 abort
- 停止按钮：AbortController.abort() 触发，UI 回到 idle
- 错误（mock 注入 HTTP 500 或 SSE error event）→ ErrorState 渲染 + 重试
- 保存模板：localStorage key `agent-gateway.playground.templates` 持久化 JSON，刷新后仍可载入
- 测试 ≥10 用例全绿：`npx vitest run tests/Playground.test.tsx`
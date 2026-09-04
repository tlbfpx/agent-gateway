# Design: Prompt Playground

## 路由 / 组件

```
src/
  pages/
    Playground.tsx                       # 主页：表单 + 输出 + 按钮
  components/playground/
    ProviderSelector.tsx                 # provider + model 二级级联下拉
    ParamSlider.tsx                      # 单参数滑块 (label + slider + 数值)
    ComparePane.tsx                      # Compare 模式下的独立 pane（左/右各一份）
    TokenBadge.tsx                       # tokens + latency + finish_reason 角标
  lib/api/
    playground.ts                        # runPlayground(params, onChunk) SSE
  components/framework/Sidebar.tsx        # +1 menu item
src/routes.tsx                           # +1 <Route path="playground">
```

## 数据流

```
Playground.tsx
  ├── state: mode ('single'|'compare'), prompt, system, temperature, topP, maxTokens
  ├── state: 左侧 modelA params + 输出 / 右侧 modelB params + 输出
  ├── onRun → runPlayground(paramsA, onChunkA, onDoneA)
  │             → fetch POST /v1/chat/stream { sessionId:null, model, prompt, system,
  │                                              temperature, topP, maxTokens }
  │             → 解析 SSE event=chunk|done|error
  ├── AbortController per pane → stop() 触发 abort
  └── 保存模板：localStorage 'agent-gateway.playground.templates' → [{ id,name,system,user,params }]
```

### 后端契约（复用，不变更）

`POST /v1/chat/stream` 已支持 `model + prompt + sessionId`。
Playground 客户端扩展 body（多带 system/temperature/topP/maxTokens 4 个字段）：
- mock server 现状只读 `prompt`，多带字段不影响测试
- 真实后端 ChatOrchestrator 是否消费这些字段不在本 Round 范围（TODO 留 Round 11）

## 测试设计

`tests/Playground.test.tsx` 10 用例：

1. mount 渲染：4 个 slider + 2 个 textarea + 2 个 select 可见
2. ProviderSelector 切换 provider → model 下拉内容刷新（gpt-4o / claude-3.7）
3. 单模型模式运行：mock SSE 返回 chunk → 输出区累积文字
4. 单模型模式：done event → TokenBadge 显示 tokens + latency
5. Compare 模式运行：左右 pane 各自独立 stream 累积
6. 停止按钮：AbortController.abort → fetch mock 的 signal abort 触发 → UI 回 idle
7. HTTP 500 错误：ErrorState 显示 + 重试按钮
8. SSE error event：ErrorState 显示
9. 保存模板：点击保存 → localStorage 写入 + 下拉显示模板名
10. 载入模板：刷新页面后模板仍在 → 选中 → 表单自动填充

## 复用 / 不重写

- ProviderSelector 复用 `listPublicModels`（已有 mock），不引新模型数据源
- TokenBadge 复用 Chat.tsx 的 `tokensIn/tokensOut/cacheHit` 展示样式
- SSE 解析逻辑参考 `lib/api/chat.ts` 的 streamChat，但扩展字段不同

## 约束

- **不改 ChatOrchestrator**
- **不新增后端 controller**（本 Round 复用 `/v1/chat/stream`）
- localStorage 键名加前缀 `agent-gateway.playground.*` 防冲突
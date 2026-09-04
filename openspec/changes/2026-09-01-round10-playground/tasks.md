# Tasks: Prompt Playground (原子拆分)

## 阶段 1：脚手架（OpenSpec + 数据）
1. 写 OpenSpec 4 件套（proposal/design/spec/tasks）— 完成
2. 读 `src/pages/Chat.tsx` SSE 模式 + `src/lib/api/chat.ts` streamChat — 完成
3. 读 mock server fixture + seed — 完成

## 阶段 2：API 层
5. 创建 `src/lib/api/playground.ts`：`runPlayground(params, callbacks) → StreamCall`
   - 复用 fetch + AbortController 模式
   - 扩展 body：system, temperature, topP, maxTokens
   - 复用 mock `POST /chat/stream` 路由

## 阶段 3：子组件
6. `src/components/playground/ProviderSelector.tsx`：provider + model 二级 Select
7. `src/components/playground/ParamSlider.tsx`：label + antd Slider + 数值显示
8. `src/components/playground/TokenBadge.tsx`：tokens + latency + finishReason
9. `src/components/playground/ComparePane.tsx`：左/右独立 pane（复用 ProviderSelector/ParamSlider/TokenBadge）

## 阶段 4：主页面
10. `src/pages/Playground.tsx`
    - mode 单/对比 Segmented
    - 表单：4 ParamSlider + 2 TextArea + ProviderSelector
    - 输出区（单模式）/ 双 pane（对比模式）
    - 运行/停止按钮（AbortController）
    - 「保存为模板」 → localStorage
    - 「载入模板」 Select

## 阶段 5：路由 + 菜单
11. `src/routes.tsx` 注册 `/playground`
12. `src/components/framework/Sidebar.tsx` 加菜单项（icon = ExperimentOutlined）

## 阶段 6：测试
13. `tests/Playground.test.tsx` 10 用例
    - mock SSE chunk / done / error / HTTP 500
    - localStorage 持久化验证
14. 跑 `npx vitest run tests/Playground.test.tsx` 全绿
15. 跑 `npx tsc -b --noEmit` 类型检查通过

## 验证

- `npx vitest run tests/Playground.test.tsx` 10/10 绿
- 全量测试 `npx vitest run` 不退化（已有 Chat / Cost / Traces 等不能挂）
- `npx tsc -b --noEmit` 无错
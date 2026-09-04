# Spec: Prompt Playground

## Requirements

### PG-001 Playground 路由可达
- `GET /playground` 渲染 Playground 页（不报 404）
- Sidebar 出现 `实验台 / Playground` 菜单项，icon = `ExperimentOutlined`
- 单击菜单项路由跳转生效

### PG-002 Provider + Model 二级选择
- Playground 顶部有 Provider 下拉（来自 `listPublicModels` 数据集去重）
- 选 provider 后 Model 下拉刷新为该 provider 下的模型
- 切到 Compare 模式时，左右各自一份 provider + model 独立

### PG-003 Prompt 输入区
- system prompt TextArea（多行，2 行起）
- user prompt TextArea（多行，2 行起，Enter 不发送，避免误触）
- 单模型模式下，两个 textarea 共用
- Compare 模式下，两个 textarea 共享（左右用同一份 prompt 测试）

### PG-004 参数调节 Slider
- 温度 Slider：范围 0-2，步长 0.05，默认 0.7
- topP Slider：范围 0-1，步长 0.01，默认 1.0
- maxTokens Slider：范围 256-32000，步长 256，默认 2048
- Compare 模式下，左右 pane 各自 4 个 slider 独立

### PG-005 运行 / 停止按钮
- 单模型模式：单击「运行」→ AbortController 创建 → fetch /v1/chat/stream
- 运行时按钮变为「停止」+ danger 样式，单击 → `ctl.abort()` → fetch 取消
- 停止后 UI 立即回 idle，已累积文本保留（与 Chat.tsx 一致）

### PG-006 Compare 模式
- 顶部有「单模式 / 对比模式」Segmented 切换控件
- Compare 模式下显示左右两个 ComparePane，每个 pane 有独立
  - provider + model + 4 sliders + 运行/停止按钮 + 输出区 + TokenBadge

### PG-007 流式响应渲染
- SSE `event: chunk` 触发 → 输出区追加 content
- SSE `event: done` 携带 `meta.tokensIn/tokensOut` → TokenBadge 显示
- 流中显示光标动画（与 Chat.tsx 一致）

### PG-008 Token / 延迟统计
- TokenBadge 显示：tokensIn ↑ / tokensOut ↓ / latencyMs / finishReason
- latencyMs 由前端从运行开始到 done 事件 `performance.now()` 差值计算
- done 事件 meta 缺省时角标显示「—」

### PG-009 错误兜底
- HTTP 4xx/5xx → ErrorState 组件渲染，显示 `HTTP <status>` + 重试按钮
- SSE `event: error` → ErrorState 显示 `data.message` + 重试
- 网络中断 / 流断开（无 done） → 视为已完成，保留已累积文本

### PG-010 模板保存
- 「保存为模板」按钮 → 弹出输入框收名字 → 写入 localStorage
- key: `agent-gateway.playground.templates`
- value: JSON `[{ id, name, system, user, temperature, topP, maxTokens, model }]`
- 模板下拉显示所有已保存模板，选中后表单自动填入
- 刷新页面后模板仍在

## 非功能性

- 兼容 jsdom 25 测试环境（fetch + AbortController 已 polyfill）
- TypeScript 严格模式无 any 泄漏
- 单一职责：playground.ts 仅做 SSE 调用，UI 状态在 Playground.tsx 维护
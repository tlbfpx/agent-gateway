# Round 10 · 代码生成器（cURL / Python / JS / Go SDK 片段）

## 背景

用户拿到 API Key 后，下一步就是「调通第一个请求」。当前 gateway-ui 在 4 个调用 API 的入口处
各有短板：

| 入口 | 现状 | 问题 |
|------|------|------|
| `ApiExplorer` 顶部「OpenAI 兼容模式」 | 只有 `cURL` + `Python` 两个写死字符串 | 不可切换 JS / Go；不跟用户实际 `apiKey` 实时同步 |
| `ApiExplorer` 端点 `Try it` | 只展示响应体 | 用户想复用请求时只能肉眼照抄，无一键复制 |
| `ApiKeys/List.tsx` | 列表只显示 `maskKey` | 想知道「拿到这个 Key 怎么调第一个请求」要去翻文档 |
| `Models/List.tsx` | drawer 只显示 `endpoint / apiKey` | 配完模型后不知怎么验证连通 |

OpenSpec 评审附录 B-3：「让代码生成成为产品的'一等地砖'，每个调用入口都应可一键复制」。
参考 Stripe / Postman / OpenAPI Generator：标准做法是
**`Tabs(curl/python/js/go) + copyable Paragraph`**，由统一的 `generateCode()` 函数驱动。

## 目标

- 新增 `src/lib/codegen.ts` —— 纯函数 `generateCode(req, lang)`，支持 4 种语言。
- 新增 `src/components/CodeSnippet.tsx` —— antd `Tabs` 包装，4 种语言 + 一键复制。
- 接入 4 个页面：ApiExplorer Try-it 抽屉、ApiKeys 行内 Popover、Models 配置 drawer、Chat 页内嵌（Playground 兜底）。
- 单测覆盖：4 语言 × 简单 / header / query / body 组合 = 8 用例；CodeSnippet 渲染 + Tab 切换 + 复制 = 3 用例。

## 方案概要

| 变更 | 文件 | 说明 |
|------|------|------|
| 新增 | `src/lib/codegen.ts` | `CodegenRequest` 类型 + `generateCode(req, lang)` |
| 新增 | `src/components/CodeSnippet.tsx` | 4-Tab + copyable Paragraph 渲染 |
| 新增 | `src/lib/codegen.test.ts` | 8 用例 |
| 新增 | `src/components/CodeSnippet.test.tsx` | 3 用例（jsdom + clipboard mock） |
| 修改 | `src/pages/ApiExplorer.tsx` | Try-it 区域底部加 `<CodeSnippet request={...}>` |
| 修改 | `src/pages/ApiKeys/List.tsx` | 行操作列加「代码」按钮 → Popover 4 语言 |
| 修改 | `src/pages/Models/List.tsx` | drawer 底部加「测试连通」区显示请求代码 |

## 非目标

- 不实现真实 SDK 客户端（不接入 axios / undici / http2 等运行时），只生成用户**可粘贴运行**的代码片段。
- 不引入 monaco / shiki 等重量级编辑器；沿用 antd `Typography.Paragraph copyable` + `<pre className="mono">`。
- 不动后端、不动 OpenAPI 协议。
- 不做请求实际发送（ApiExplorer Try-it 已有，Models 沿用相同模式即可）。

## 影响面

- 用户可感知：4 个入口都能一键复制 4 种语言代码；零额外依赖。
- 包体积：`generateCode` 纯函数 ~150 行，运行时只调 antd Tabs/Typography，已是 antd 内置组件。
- 测试：vitest `jsdom` 环境 + 自带 clipboard mock（setup.ts 已有 stub 基础）。
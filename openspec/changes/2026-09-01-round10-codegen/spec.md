# Spec · 代码生成器需求规格

## ADDED Requirements

### REQ-1: 核心生成函数

The system SHALL provide `generateCode(request, lang)` that produces a complete, runnable code
snippet for one of 4 supported languages (`curl`, `python`, `js`, `go`).

**Acceptance:**
- `request.method` ∈ {GET, POST, PUT, DELETE, PATCH}
- `request.url` 非空
- `request.headers` 自动转译为各语言的 header 声明
- `request.body` 对象自动 `JSON.stringify`；字符串原样
- `request.query` 非空且 `request.url` 不含 `?` 时自动追加
- `request.apiKey` 存在且 `headers` 中无 `X-API-Key` 时自动注入

### REQ-2: CodeSnippet 组件

The system SHALL provide `<CodeSnippet request={...} defaultLang="curl" />` that renders an
antd Tabs with 4 panels, displaying the generated snippet in a copyable Paragraph.

**Acceptance:**
- Tabs 显示 `curl / python / js / go` 4 个 label
- 默认展示 `defaultLang`
- 每 Panel 有 antd `Typography.Paragraph copyable`
- 点击复制图标调用 `navigator.clipboard.writeText(generateCode(req, lang))`

### REQ-3: ApiExplorer Try-it 集成

When the user opens any endpoint Try-it drawer, the system SHALL display the equivalent
`<CodeSnippet request={...}>` so the user can copy a runnable snippet reflecting the
exact parameters they entered.

### REQ-4: ApiKeys List 行内集成

For each row in the ApiKeys list, the system SHALL display a "代码" button that opens a
Popover containing `<CodeSnippet request={...}>` with `apiKey=<key>` and `url=/v1/chat/completions`.

### REQ-5: Models drawer 集成

When editing/creating a model in the drawer, the system SHALL display a "使用示例" section
containing `<CodeSnippet request={...}>` targeting the configured `endpoint` with a placeholder API Key.

### REQ-6: Chat 页内嵌（Playground 兜底）

When the Chat page is open, the system SHALL display a collapsible "请求示例" section at the
bottom of the conversation area, rendering `<CodeSnippet request={...}>` for the last
sent message with method POST `/v1/chat/completions`.

**Note:** If `src/pages/Chat.tsx` does not exist or its structure makes integration infeasible,
this requirement is automatically satisfied via the Playground fallback path documented
in `design.md §5`.

### REQ-7: 测试覆盖

The system SHALL provide:
- `src/lib/codegen.test.ts` with **8** test cases (4 languages × baseline POST)
- `src/components/CodeSnippet.test.tsx` with **3** test cases (renders 4 tabs, switches,
  clipboard)

## MODIFIED Requirements

无（保留所有现有行为）。

## REMOVED Requirements

无。

## Cross-cutting

- 仅前端变更；不动 backend、不动 OpenAPI 协议。
- 仅依赖 antd `Tabs` / `Typography` / `Popover` / `Drawer`；不引入新 npm 包。
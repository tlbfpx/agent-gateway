# Design · 代码生成器

## 1. 模块边界

```
┌─────────────────────────────────────────────────────────────┐
│  pages/ApiExplorer.tsx                                       │
│      └─ <CodeSnippet request={tryItReq} />                  │
│  pages/ApiKeys/List.tsx                                      │
│      └─ <Popover><CodeSnippet ... /></Popover>               │
│  pages/Models/List.tsx                                       │
│      └─ <Drawer ...><CodeSnippet request={testReq} /></Drawer>│
└─────────────────────────────────────────────────────────────┘
                          ↓ props
┌─────────────────────────────────────────────────────────────┐
│  components/CodeSnippet.tsx                                  │
│      Tabs(curl/python/js/go)                                 │
│       └─ <Typography.Paragraph copyable>{generateCode(...)}│
│                          ↑ 调                                │
│  lib/codegen.ts                                              │
│      generateCode(req, lang) → string                        │
└─────────────────────────────────────────────────────────────┘
```

## 2. 类型契约

```ts
// src/lib/codegen.ts
export type CodegenLang = 'curl' | 'python' | 'js' | 'go';

export interface CodegenRequest {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  url: string;                              // 完整 URL，可含 query 占位
  headers?: Record<string, string>;        // 顺序敏感：保留插入序
  query?: Record<string, string | number>;  // 可选：单独拼到 url 后面
  body?: unknown;                           // 对象 → JSON.stringify；字符串原样
  apiKey?: string;                          // 可选：自动注入 Authorization
}

export function generateCode(req: CodegenRequest, lang: CodegenLang): string;
```

## 3. 关键决策

### D1. URL 拼接：`url` 已含 query vs `query` 单独字段？

`url` 优先；若 `query` 非空且 `url` 未含 `?` 则追加。
避免双写时重复 `?`。

### D2. body 序列化

- `body` 是字符串 → 原样（已 JSON 化的源码可保留缩进）
- `body` 是对象 → `JSON.stringify(body)`
- `body` 是 undefined / null → 跳过 `-d '...'` 段

### D3. API Key 注入策略

**不主动注入 `Authorization` header**——把决定权交给调用方：
- 调用方已填 `headers` → 完全尊重
- 调用方传了 `apiKey` 但没填 header → 自动加 `X-API-Key: <key>`（与网关约定一致）
- Go / Python / JS 各语言按惯例用 `X-API-Key` 占位（避免 OAuth Bearer 误用）

### D4. 输出风格

- cURL：单引号包 URL，单引号包 body（body 内 `'` → `'\''`）
- Python：`requests` 单文件脚本，无依赖版本注释
- JS：`fetch` + `await`，可直接 copy 进 `.mjs`
- Go：标准库 `net/http` + `bytes.NewBuffer`，无外部 import

## 4. 错误处理

`generateCode` 是纯函数，无 IO，**不抛异常**。
若 `url` 为空仍返回提示行（`'# url is empty'`），便于调用方早发现。

## 5. 集成点详解

### ApiExplorer Try-it 抽屉
现有 `TryIt` 组件已有 `submit()` 拼接 url/headers/body 的逻辑。
**新增**：`buildRequest()` 把同样数据传给 `<CodeSnippet>`，使得用户复制出的代码
**与实际发送的请求一致**。

### ApiKeys 行
新增「代码」按钮 → `<Popover trigger="click">` 展示 4 语言代码。
Popover content 用 `<CodeSnippet>`，`defaultLang="curl"`。

### Models drawer
底部加「使用示例」区，`endpoint` 直接来自表单，
`apiKey` 默认 `<YOUR_MODEL_KEY>` 占位（密码字段不回填）。

## 6. 测试策略

- `lib/codegen.test.ts`：8 用例（4 语言 × 简单 POST）
- `components/CodeSnippet.test.tsx`：3 用例
  - 渲染 4 Tab
  - 切换 Tab → 内容更新
  - 点击复制 → `navigator.clipboard.writeText` 被调用

clipboard mock：jsdom 不自带 clipboard API，在测试 setup 或组件测试内 stub。
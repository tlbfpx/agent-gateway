# Tasks · 代码生成器

## 1. OpenSpec 4 件套（已完成）

- [x] `proposal.md`
- [x] `design.md`
- [x] `spec.md`
- [x] `tasks.md`（本文）

## 2. 实现

### T1. `src/lib/codegen.ts`
- [ ] 定义 `CodegenLang` / `CodegenRequest` 类型
- [ ] 实现 URL 拼接（query 自动追加）
- [ ] 实现 4 种语言生成器
  - [ ] `curl`: `-X METHOD 'url' -H 'k: v' -d 'body'`
  - [ ] `python`: `import requests` + `requests.request(method, url, ...)`
  - [ ] `js`: `await fetch(url, {method, headers, body: JSON.stringify(...)})`
  - [ ] `go`: `http.NewRequest("METHOD", url, bytes.NewBuffer([]byte(bodyJson)))`
- [ ] `apiKey` 自动注入 `X-API-Key`（若 `headers` 未含）
- [ ] body 字符串转义（cURL 单引号、Go `[]byte`）

### T2. `src/components/CodeSnippet.tsx`
- [ ] props `{ request: CodegenRequest, defaultLang?: CodegenLang }`
- [ ] antd `Tabs` 4 个 panel
- [ ] 每 panel 渲染 `<Typography.Paragraph copyable>` 内含生成代码
- [ ] 复制成功 toast

### T3. `src/pages/ApiExplorer.tsx` 集成
- [ ] 提取 `buildRequest()` 共用逻辑（url/headers/body）
- [ ] Try-it 区域底部追加 `<CodeSnippet request={...} />`
- [ ] 既不影响发送按钮逻辑，也不重复 body 渲染

### T4. `src/pages/ApiKeys/List.tsx` 集成
- [ ] 行操作列追加「代码」按钮
- [ ] `<Popover trigger="click" content={<CodeSnippet .../>}>`
- [ ] request: `POST /v1/chat/completions` + `apiKey=k.id`

### T5. `src/pages/Models/List.tsx` 集成
- [ ] drawer 底部追加 `<CodeSnippet request={testReq} />`
- [ ] target endpoint 取自表单 `endpoint` 字段
- [ ] API Key 用 `<YOUR_MODEL_KEY>` 占位

### T6. 测试
- [ ] `src/lib/codegen.test.ts` 8 用例
- [ ] `src/components/CodeSnippet.test.tsx` 3 用例

## 3. 验收

- [ ] `npm run typecheck` 通过
- [ ] `npm test` 全部 PASS（包含新增的 11 用例）
- [ ] `npm run build` 成功
- [ ] 4 个页面手动验证（dev server）：每个入口点开都能看到 4 语言 Tab + 复制按钮

## 4. 风险与回滚

- **风险 1**：jsdom 不实现 `navigator.clipboard.writeText`，需在测试 setup stub。
- **风险 2**：antd `Typography.Paragraph copyable` 在 jsdom 下需要 `Tooltip` 支持；
  沿用项目既有 antd jsdom 测试成功案例（如 `useResourceList.test.ts` 旁加载的 `Empty`）。
- **回滚**：git revert 本 change 的所有 commit（按 OpenSpec 惯例，change 不打 tag 时
  走 PR review 后 squash 合并；本 change 已说明「不 commit」，由 orchestrator 统一处理）。
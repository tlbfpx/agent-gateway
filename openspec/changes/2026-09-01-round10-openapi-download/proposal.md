# Proposal: 一键下载 OpenAPI 客户端产物（DX 最后一公里）

> **状态**：Round 10 新能力，OpenSpec 4 件套 + 后端 controller + 前端 BundleDownloader
> **来源**：Round 9 收尾调研 —— 用户看到 `/v1/openapi.json` 后还要手动跑 openapi-generator 才能拿到 SDK，DX 链断在最后一公里。

## 动机

Agent Gateway 已经暴露 `/v1/openapi.json` 规范，但集成方需要：
1. 装 openapi-generator-cli（npx / npm / mvn / docker 任意一种）
2. 写命令 `openapi-generator-cli generate -i openapi.json -g typescript-fetch -o sdk/`
3. 解压、安装依赖、才能在自家项目里 `import`

这对内部业务方（10+ 团队）门槛过高，**大多数团队直接放弃 SDK 化**，转用 `curl` 手糊。

本期目标是：在 Admin UI 的 **API 浏览器**页面顶部直接给三个按钮 **「下载 Python SDK / TypeScript SDK / Go SDK」**，点击即弹出浏览器下载（zip 流），解压即用。

## What

### 后端（gateway-interfaces/openapi 新建）

- `OpenApiBundleController` —— 提供两个端点：
  - `GET /v1/openapi/bundle?lang=python|typescript|go` —— 返回预生成的 zip（application/zip + Content-Disposition: attachment）
  - `GET /v1/openapi/bundle/langs` —— 返回 `{"langs": ["python","typescript","go"], "available": {"python": true, ...}}`
- 产物以 **classpath 资源** 形式打包进 jar：`gateway-interfaces/src/main/resources/openapi-bundles/{python,typescript,go}.zip`
- 启动时校验资源存在；缺失则请求返回 **503**（不是 500），错误体为 `{"error":"bundle_unavailable","lang":"python"}`

### 占位 zip 策略（Round 10 简化）

按约束，**首期 zip 是 placeholder**（每个 zip 内含 README + 一个示例 .py/.ts/.go 文件，README 说明用法与「真实生成将随 Round 11 注入」），后端 controller 与前端 BundleDownloader 均按真实 zip 流式下载设计，Round 11 替换 zip 文件即可，**前端零改动**。

### 前端（agent-gateway-ui）

- `src/components/openapi/BundleDownloader.tsx` —— 新组件，3 个 Ant Design Button
- `src/lib/api/openapi.ts` —— 追加 `getOpenApiBundleUrl(lang)` + `getOpenApiBundleBlob(lang)`（既有 fetchOpenApi 不动）
- `src/pages/ApiExplorer.tsx` —— 顶部操作区追加 BundleDownloader（在「刷新」按钮旁）
- `tests/BundleDownloader.test.tsx` —— 渲染 3 按钮 + 点击触发 fetch + 校验 blob 写入

## Non-goals

- 不做「在线 swagger-ui」接入（既有 ApiExplorer 已有自研 viewer）
- 不做 npm/pip 发布（仅 zip 下载到本地）
- 不做 zip 实时生成（无运行时 openapi-generator 依赖；预生成产物落 jar 资源）
- 不做 hash 校验 / 签名（zip 内容来自受信 classpath）
- 不做 Round 11 的真实产物注入（待后续 PR 替换 zip 文件）

## 验收

- 后端：`mvn -pl gateway-interfaces,gateway-bootstrap -am test` BUILD SUCCESS
- 前端：`npx vitest run` 全绿，新增 BundleDownloader 测试 3+ 例
- UI：ApiExplorer 顶部显示 3 个下载按钮；点击触发浏览器下载 zip
- 缺失资源：访问 `/v1/openapi/bundle?lang=foo` 返回 503 + 明确错误体

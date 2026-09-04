# Spec: OpenAPI 客户端产物下载（可测试条款）

#### GW-OAB-001 下载端点
**MUST**：`GET /v1/openapi/bundle?lang=python` 返回 200，`Content-Type: application/octet-stream`，`Content-Disposition: attachment; filename="agent-gateway-python-sdk.zip"`，body 为合法 zip 字节流。
**测试**：OpenApiBundleControllerTest.downloadPythonReturnsZip。

#### GW-OAB-002 多语言
**MUST**：`lang` 取值支持 `python` / `typescript` / `go` 三种（小写敏感），每种返回对应 zip。
**测试**：downloadPythonReturnsZip / downloadTypescriptReturnsZip / downloadGoReturnsZip。

#### GW-OAB-003 未知 lang
**MUST**：`lang` 取值不在白名单（python/typescript/go）时返回 400，body 含明确错误信息。
**测试**：unknownLangReturns400。

#### GW-OAB-004 资源缺失 503
**MUST**：请求的语言 zip 在 classpath 不存在时返回 503（SERVICE_UNAVAILABLE），body 含 `error` 字段。**注意：缺失 zip 时服务必须降级优雅而非 500**。
**测试**：missingResourceReturns503（用 stub ClassPathResource override 或 by 删除资源 + 重建 resource-less jar 验证；实现选择：用 `@PostConstruct` 检查并落 state，测试用 `controller.isAvailable(lang)` 校验）。

#### GW-OAB-005 langs 端点
**MUST**：`GET /v1/openapi/bundle/langs` 返回 `{"langs":["python","typescript","go"],"available":{"python":true,"typescript":true,"go":true}}`，available 反映 classpath 真实存在性。
**测试**：langsReturnsAllThreeAvailability。

#### GW-OAB-006 Content-Disposition 文件名
**MUST**：`Content-Disposition` 头必须含正确文件名：`agent-gateway-{lang}-sdk.zip`，浏览器直接触发下载。
**测试**：contentDispositionHeaderIsCorrect。

#### GW-OAB-007 前端按钮渲染
**MUST**：BundleDownloader 组件渲染 3 个 antd Button，文案为「下载 Python SDK / 下载 TypeScript SDK / 下载 Go SDK」。
**测试**：BundleDownloaderTest.rendersThreeButtons。

#### GW-OAB-008 前端点击下载
**MUST**：点击任一按钮 → 调 `fetch(/v1/openapi/bundle?lang=X)`，成功后将 blob 通过 `<a download>` 触发浏览器下载；fetch 失败时 antd `message.error` 显示。
**测试**：BundleDownloaderTest.clickTriggersDownload / clickFailureShowsError。

#### GW-OAB-009 接入 ApiExplorer
**MUST**：ApiExplorer.tsx 顶部操作区包含 BundleDownloader 组件。
**测试**：ApiExplorer.test.tsx（如新增）含 BundleDownloader；或 CodeGrep 验证 import。

#### GW-OAB-010 鉴权透传
**MUST**：前端 fetch bundle 时必须带 `X-API-Key` header（与既有 /v1 API 一致）。
**测试**：BundleDownloaderTest.requestIncludesApiKeyHeader。

# Tasks: OpenAPI 客户端产物下载（round10-openapi-download）

## A. OpenSpec 4 件套
- [x] **A.1** proposal.md / design.md / spec.md / tasks.md 落 `openspec/changes/2026-09-01-round10-openapi-download/`

## B. 后端实现
- [ ] **B.1** 创建 `OpenApiBundleController`（路径 `/v1/openapi/bundle`）
  - GET `?lang=python|typescript|go` → 200 + zip 流
  - GET `/langs` → 返回 lang 列表 + 资源可用性
- [ ] **B.2** 创建 classpath 占位 zip 资源 3 份
  - `src/main/resources/openapi-bundles/python.zip` —— README.md + client.py
  - `src/main/resources/openapi-bundles/typescript.zip` —— README.md + client.ts
  - `src/main/resources/openapi-bundles/go.zip` —— README.md + client.go
- [ ] **B.3** 单元测试 `OpenApiBundleControllerTest`（≥6 例）：3 语言 + 未知 lang 400 + 资源缺失 503 + langs

## C. 前端实现
- [ ] **C.1** `src/lib/api/openapi.ts` 追加 `getOpenApiBundleUrl` / `getOpenApiBundleBlob` / `downloadOpenApiBundle`
- [ ] **C.2** `src/components/openapi/BundleDownloader.tsx` 新建（3 按钮 + loading + error toast）
- [ ] **C.3** `src/pages/ApiExplorer.tsx` 顶部 Space 追加 `<BundleDownloader />`
- [ ] **C.4** `tests/BundleDownloader.test.tsx` 新建（≥3 例：渲染 3 按钮 / 点击触发 fetch / 失败 message）

## D. 验收
- [ ] **D.1** `mvn -pl gateway-interfaces,gateway-bootstrap -am test` BUILD SUCCESS（gateway-interfaces 单测 + bootstrap sanity）
- [ ] **D.2** `npx vitest run` 全绿（含 BundleDownloader）
- [ ] **D.3** Spec 条款 GW-OAB-001 ~ GW-OAB-010 全部覆盖

## Round 10 后续（P2）
- GW-OAB-004 真实生成：Round 11 PR 替换 3 个 zip 为 `openapi-generator-cli` 真实产物（python-pydantic / typescript-fetch / go），前端零改动
- 增加 `langs/manifest.json` 资源（含 size / sha256 / 版本号）便于前端展示文件大小
- SDK 自动发布到内部 Nexus / npm registry（脱离 zip 下载）

# Design: 一键下载 OpenAPI 客户端产物

## 架构

```
┌─────────────────────────────┐                          ┌──────────────────────────────┐
│  agent-gateway-ui           │                          │  gateway-bootstrap           │
│  BundleDownloader.tsx       │  GET /v1/openapi/bundle? │  → OpenApiBundleController   │
│  ─ 点击 Button              │ ───────────────────────▶ │    ─ 读 classpath 资源        │
│  ─ getOpenApiBundleBlob()   │   lang=python            │      openapi-bundles/python  │
│  ─ saveAs zip               │ ◀─────────────────────── │      .zip                     │
│                             │   application/zip        │    ─ Resource → InputStream  │
│                             │                          │    ─ ResponseEntity<byte[]>   │
└─────────────────────────────┘                          └──────────────────────────────┘
```

## 后端实现

### OpenApiBundleController（新建）

```java
@RestController
@RequestMapping("/v1/openapi/bundle")
public class OpenApiBundleController {

    private static final Map<String, String> BUNDLE_PATHS = Map.of(
        "python", "openapi-bundles/python.zip",
        "typescript", "openapi-bundles/typescript.zip",
        "go", "openapi-bundles/go.zip"
    );

    @GetMapping
    public ResponseEntity<byte[]> download(@RequestParam String lang) {
        String path = BUNDLE_PATHS.get(lang.toLowerCase());
        if (path == null) {
            throw new ResponseStatusException(BAD_REQUEST, "unsupported lang: " + lang);
        }
        Resource res = new ClassPathResource(path);
        if (!res.exists()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "bundle_unavailable");
        }
        byte[] bytes;
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "read failed");
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"agent-gateway-" + lang + "-sdk.zip\"")
            .body(bytes);
    }

    @GetMapping("/langs")
    public Map<String, Object> langs() {
        Map<String, Boolean> availability = new LinkedHashMap<>();
        for (var e : BUNDLE_PATHS.entrySet()) {
            availability.put(e.getKey(), new ClassPathResource(e.getValue()).exists());
        }
        return Map.of("langs", List.of("python","typescript","go"), "available", availability);
    }
}
```

### Zip 占位内容（Round 10）

每个 zip 含：
- `README.md` —— 说明「这是 placeholder SDK；真实生成由 Round 11 PR 注入；当前可用但不含完整类型」+ `pip install` / `npm install` / `go mod tidy` 接入指引
- 一个最小 `client.py` / `client.ts` / `client.go` 示例（含一个 `health()` 方法，调 `/v1/health`），让用户能 `python client.py` 跑通端到端

### 为什么用 classpath 资源而不是文件系统

1. 部署一次 zip 就打包进 jar，无需运行时 IO 权限
2. 不需要外部脚本（`openapi-generator-cli`）在运行时执行，简化部署
3. Round 11 真实生成替换 zip 即可，前端零改动

### 为什么不在 springdoc 之外另起 OpenAPI 生成

既有 `OpenApiController` 已手写维护 `/v1/openapi.json`（spec §23.4 一期）。本次下载的 SDK 基于此 spec 生成（首期是手写 placeholder，Round 11 起用真实 generator）。

## 前端实现

### lib/api/openapi.ts（追加，不破坏既有）

```ts
export type BundleLang = 'python' | 'typescript' | 'go';

export function getOpenApiBundleUrl(lang: BundleLang): string {
  return `/v1/openapi/bundle?lang=${lang}`;
}

export async function getOpenApiBundleBlob(lang: BundleLang): Promise<Blob> {
  const apiKey = getApiKey();
  const res = await fetch(getOpenApiBundleUrl(lang), {
    headers: apiKey ? { 'X-API-Key': apiKey } : {},
  });
  if (!res.ok) throw new Error(`bundle HTTP ${res.status}`);
  return res.blob();
}

export async function downloadOpenApiBundle(lang: BundleLang): Promise<void> {
  const blob = await getOpenApiBundleBlob(lang);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `agent-gateway-${lang}-sdk.zip`;
  a.click();
  URL.revokeObjectURL(url);
}
```

### BundleDownloader.tsx

3 个 Ant Design `<Button>`，点击后调 `downloadOpenApiBundle`，loading/error 状态由组件自己管。

### ApiExplorer.tsx 接入

顶部 `<Space>` 追加 BundleDownloader（在「刷新」按钮前）。

## 测试

### 后端 OpenApiBundleControllerTest

- `python 返回 zip + content-type application/octet-stream`
- `typescript 返回 zip`
- `go 返回 zip`
- `lang=foo 返回 400`
- `lang=missing 返回 503`（mock classpath 不存在）
- `langs 返回 3 个 lang + available 状态`

### 前端 BundleDownloader.test.tsx

- 渲染 3 按钮（Python / TypeScript / Go）
- 点击 Python → 触发 fetch 且下载文件
- mock fetch 失败 → 显示错误 message

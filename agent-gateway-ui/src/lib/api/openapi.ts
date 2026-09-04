/**
 * openapi.ts — OpenAPI 文档拉取与解析
 */
import { http, getApiKey } from '../request';

export interface OpenApiDoc {
  openapi: string;
  info: { title: string; version: string; description?: string };
  servers?: { url: string }[];
  components?: Record<string, unknown>;
  paths: Record<string, Record<string, OpenApiOp>>;
}

export interface OpenApiOp {
  summary?: string;
  description?: string;
  tags?: string[];
  operationId?: string;
  parameters?: OpenApiParam[];
  requestBody?: OpenApiRequestBody;
  responses?: Record<string, OpenApiResponse>;
  security?: { ApiKeyAuth?: string[] }[];
}

export interface OpenApiParam {
  name: string;
  in: 'query' | 'path' | 'header' | 'cookie';
  required?: boolean;
  description?: string;
  schema?: { type?: string; default?: unknown };
  example?: unknown;
}

export interface OpenApiRequestBody {
  required?: boolean;
  content: Record<string, { schema?: OpenApiSchema }>;
}

export interface OpenApiResponse {
  description?: string;
  content?: Record<string, { schema?: OpenApiSchema }>;
}

export interface OpenApiSchema {
  type?: string;
  properties?: Record<string, OpenApiSchema>;
  required?: string[];
  example?: unknown;
  $ref?: string;
}

export async function fetchOpenApi(): Promise<OpenApiDoc> {
  // 与 request.ts 不同：openapi.json 不需要 X-Tenant-Id，避免无谓副作用
  const res = await fetch('/v1/openapi.json');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as OpenApiDoc;
}

export interface ApiEndpoint {
  method: string;
  path: string;
  summary: string;
  description: string;
  tags: string[];
  op: OpenApiOp;
}

/** 把 paths 摊平为端点数组 */
export function flattenEndpoints(doc: OpenApiDoc): ApiEndpoint[] {
  const out: ApiEndpoint[] = [];
  for (const [path, methods] of Object.entries(doc.paths ?? {})) {
    for (const [method, op] of Object.entries(methods)) {
      if (typeof op !== 'object' || !op) continue;
      const o = op as OpenApiOp;
      out.push({
          method: method.toUpperCase(),
          path,
          summary: o.summary ?? '',
          description: o.description ?? '',
          tags: o.tags && o.tags.length > 0 ? o.tags : ['未分类'],
          op: o,
        });
    }
  }
  return out;
}

/** 按 tag 分组 */
export function groupByTag(endpoints: ApiEndpoint[]): Map<string, ApiEndpoint[]> {
  const m = new Map<string, ApiEndpoint[]>();
  for (const e of endpoints) {
    const tag = e.tags[0] ?? '未分类';
    if (!m.has(tag)) m.set(tag, []);
    m.get(tag)!.push(e);
  }
  return m;
}

void http; // 避免 tree-shake 误删 http import

// =============================================================================
// Round 10: 一键下载 SDK zip（OpenSpec 2026-09-01-round10-openapi-download）
// =============================================================================

export type BundleLang = 'python' | 'typescript' | 'go';

export const SUPPORTED_BUNDLE_LANGS: BundleLang[] = ['python', 'typescript', 'go'];

export function isBundleLang(v: string): v is BundleLang {
  return (SUPPORTED_BUNDLE_LANGS as string[]).includes(v);
}

/** 拼出 zip 下载 URL（含 lang 查询参数） */
export function getOpenApiBundleUrl(lang: BundleLang): string {
  return `/v1/openapi/bundle?lang=${encodeURIComponent(lang)}`;
}

/**
 * 流式下载 zip 二进制。带 X-API-Key（与既有 /v1 API 鉴权一致）。
 * 失败抛 Error（含 HTTP 状态码 + 错误体）。
 */
export async function getOpenApiBundleBlob(lang: BundleLang): Promise<Blob> {
  const apiKey = getApiKey();
  const headers: Record<string, string> = {};
  if (apiKey) headers['X-API-Key'] = apiKey;
  const res = await fetch(getOpenApiBundleUrl(lang), { headers });
  if (!res.ok) {
    let detail = '';
    try {
      detail = await res.text();
    } catch {
      /* ignore */
    }
    throw new Error(`bundle ${lang} HTTP ${res.status}${detail ? `: ${detail}` : ''}`);
  }
  return res.blob();
}

/**
 * 触发浏览器下载（创建临时 anchor + revokeObjectURL）。
 * 错误向上抛（由 UI 自行 toast）。
 */
export async function downloadOpenApiBundle(lang: BundleLang): Promise<void> {
  const blob = await getOpenApiBundleBlob(lang);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `agent-gateway-${lang}-sdk.zip`;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 释放 URL：Safari 需要稍等否则下载被取消
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
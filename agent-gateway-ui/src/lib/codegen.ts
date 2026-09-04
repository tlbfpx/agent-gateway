/**
 * codegen.ts — 把一个 HTTP 请求描述生成 4 种语言的代码片段
 *
 * 设计目标：
 *   1. 纯函数（无 IO、不依赖 DOM/React），便于单元测试和 SSR。
 *   2. 输出**可粘贴运行**：cURL 单引号转义、Go body 用 []byte、Python json= 自动 dict。
 *   3. 不强行注入 Authorization（尊重调用方已填的 headers）；仅在 apiKey 字段
 *      提供且 headers 未含 X-API-Key 时补一条，避免漏传。
 *
 * 参考实现：Stripe / Postman "Code snippet" / OpenAPI Generator client-gen.
 */

export type CodegenLang = 'curl' | 'python' | 'js' | 'go';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

export interface CodegenRequest {
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  /** 单独的 query 参数：非空且 url 未含 ? 时自动拼到 url 末尾 */
  query?: Record<string, string | number | boolean>;
  /** 对象 → JSON.stringify；字符串原样；undefined/null 跳过 */
  body?: unknown;
  /** 网关 API Key；headers 未含 X-API-Key 时自动注入 */
  apiKey?: string;
}

const SUPPORTED: readonly CodegenLang[] = ['curl', 'python', 'js', 'go'] as const;

export function isCodegenLang(s: string): s is CodegenLang {
  return (SUPPORTED as readonly string[]).includes(s);
}

/* ──────────────────────────── helpers ──────────────────────────── */

/** 把 query 对象按 key 排序后追加到 url 末尾（已含 ? 则只拼 &xxx） */
function appendQuery(url: string, query?: CodegenRequest['query']): string {
  if (!query) return url;
  const entries = Object.entries(query).filter(([, v]) => v !== undefined && v !== null);
  if (entries.length === 0) return url;
  const qs = entries
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
  return url.includes('?') ? `${url}&${qs}` : `${url}?${qs}`;
}

/** 把 body 统一为可拼接的字符串；undefined 视为无 body */
function serializeBody(body: unknown): string | undefined {
  if (body === undefined || body === null) return undefined;
  if (typeof body === 'string') return body;
  return JSON.stringify(body);
}

/** cURL 单引号转义：内部 ' 替换为 '\'' */
function escapeCurlSingleQuotes(s: string): string {
  return s.replace(/'/g, "'\\''");
}

/** Go 双引号字符串转义 */
function escapeGoDoubleQuotes(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

/** JS template literal 反引号转义（保险起见，cURL / Go 用其它方式） */
function escapeJsBackticks(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${');
}

/** 自动注入 X-API-Key（如未设置） */
function withApiKey(
  headers: Record<string, string> | undefined,
  apiKey?: string,
): Record<string, string> {
  const out = { ...(headers ?? {}) };
  if (apiKey && !Object.keys(out).some((k) => k.toLowerCase() === 'x-api-key')) {
    out['X-API-Key'] = apiKey;
  }
  return out;
}

/* ──────────────────────────── generators ──────────────────────────── */

function genCurl(req: CodegenRequest): string {
  const url = appendQuery(req.url, req.query);
  const headers = withApiKey(req.headers, req.apiKey);
  const bodyStr = serializeBody(req.body);

  if (!url) return '# url is empty';

  const lines: string[] = [`curl -X ${req.method} '${escapeCurlSingleQuotes(url)}'`];
  for (const [k, v] of Object.entries(headers)) {
    lines.push(`  -H '${escapeCurlSingleQuotes(`${k}: ${v}`)}'`);
  }
  if (bodyStr !== undefined && req.method !== 'GET') {
    lines.push(`  -d '${escapeCurlSingleQuotes(bodyStr)}'`);
  }
  return lines.join(' \\\n');
}

function genPython(req: CodegenRequest): string {
  const url = appendQuery(req.url, req.query);
  const headers = withApiKey(req.headers, req.apiKey);
  const bodyStr = serializeBody(req.body);

  const lines: string[] = ['import requests', ''];
  lines.push(`url = "${url}"`);
  lines.push(`headers = {`);
  const hEntries = Object.entries(headers);
  if (hEntries.length === 0) {
    lines.push('}');
  } else {
    lines.push(
      hEntries.map(([k, v]) => `    "${k}": "${v.replace(/"/g, '\\"')}"`).join(',\n') + ',',
    );
    lines.push('}');
  }
  if (bodyStr !== undefined && req.method !== 'GET') {
    // 尝试写成 dict（如果是合法 JSON），否则用 data=
    try {
      const parsed = JSON.parse(bodyStr);
      const dict = JSON.stringify(parsed, null, 4)
        .split('\n')
        .map((l, i) => (i === 0 ? l : '    ' + l))
        .join('\n');
      lines.push(`payload = ${dict}`);
      lines.push('');
      lines.push(`resp = requests.request("${req.method}", url, headers=headers, json=payload)`);
    } catch {
      lines.push(`body = ${JSON.stringify(bodyStr)}`);
      lines.push('');
      lines.push(`resp = requests.request("${req.method}", url, headers=headers, data=body)`);
    }
  } else {
    lines.push('');
    lines.push(`resp = requests.request("${req.method}", url, headers=headers)`);
  }
  lines.push('print(resp.status_code, resp.text)');
  return lines.join('\n');
}

function genJs(req: CodegenRequest): string {
  const url = appendQuery(req.url, req.query);
  const headers = withApiKey(req.headers, req.apiKey);
  const bodyStr = serializeBody(req.body);

  const lines: string[] = [];
  const init: Record<string, unknown> = { method: req.method };
  if (Object.keys(headers).length > 0) {
    init.headers = headers;
  }
  if (bodyStr !== undefined && req.method !== 'GET') {
    init.body = bodyStr; // JS 端保持字符串，调用方自行 JSON.stringify；
    // 生成时若原 body 是对象会自动 stringify 成字符串，符合 fetch 规范。
  }

  // 用 JSON.stringify(init, null, 2) 输出
  lines.push(`const resp = await fetch(${JSON.stringify(url)}, ${JSON.stringify(init, null, 2)});`);
  lines.push('const data = await resp.json();');
  lines.push('console.log(resp.status, data);');
  // 把首行之后做合理缩进
  const first = lines[0];
  const rest = lines
    .slice(1)
    .map((l) => ' '.repeat(2) + l)
    .join('\n');
  return `${first}\n${rest}`;
}

function genGo(req: CodegenRequest): string {
  const url = appendQuery(req.url, req.query);
  const headers = withApiKey(req.headers, req.apiKey);
  const bodyStr = serializeBody(req.body);

  const lines: string[] = [
    'package main',
    '',
    'import (',
    '\t"bytes"',
    '\t"fmt"',
    '\t"io"',
    '\t"net/http"',
    ')',
    '',
    'func main() {',
    `\turl := "${url}"`,
  ];

  if (bodyStr !== undefined && req.method !== 'GET') {
    lines.push(`\tbody := []byte(\`${escapeJsBackticks(bodyStr)}\`)`);
  }

  // NewRequest 第三个参数：body 有值时用 bytes.NewReader，无 body 时 nil
  let reqArgs = `http.NewRequest("${req.method}", url, `;
  if (bodyStr !== undefined && req.method !== 'GET') {
    reqArgs += 'bytes.NewReader(body)';
  } else {
    reqArgs += 'nil';
  }
  reqArgs += ')';

  lines.push(`\treq, _ := ${reqArgs}`);

  if (Object.keys(headers).length > 0) {
    lines.push('\treq.Header = http.Header{');
    for (const [k, v] of Object.entries(headers)) {
      lines.push(`\t\t"${k}": []string{"${escapeGoDoubleQuotes(v)}"},`);
    }
    lines.push('\t}');
  }

  lines.push(
    '\tresp, err := http.DefaultClient.Do(req)',
    '\tif err != nil { panic(err) }',
    '\tdefer resp.Body.Close()',
    '\tdata, _ := io.ReadAll(resp.Body)',
    '\tfmt.Println(resp.Status, string(data))',
    '}',
  );
  return lines.join('\n');
}

/* ──────────────────────────── entry ──────────────────────────── */

export function generateCode(req: CodegenRequest, lang: CodegenLang): string {
  switch (lang) {
    case 'curl':
      return genCurl(req);
    case 'python':
      return genPython(req);
    case 'js':
      return genJs(req);
    case 'go':
      return genGo(req);
    default: {
      // 编译期兜底，运行时防御
      const _exhaustive: never = lang;
      return `// unsupported lang: ${String(_exhaustive)}`;
    }
  }
}

export const CODEGEN_LANGS: readonly CodegenLang[] = SUPPORTED;
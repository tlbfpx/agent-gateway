/**
 * openaiCompat.ts — OpenAI 兼容模式调用客户端
 *
 * 让上游现有 OpenAI SDK / curl 用户零改造接入本网关：
 *   - base_url 指向 /v1/chat/completions（也支持 /v1/embeddings）
 *   - api_key 用本网关签发的 Key
 *
 * 与 chat.ts 的 streamChat 走两条独立链路，避免改动 170 行主链路导致
 * Chat.tsx 已有覆盖回归。两个核心差异：
 *   1) 鉴权：除 X-API-Key / X-Tenant-Id 外，再发 Authorization: Bearer <key>，
 *      演示 OpenAI SDK 的原生鉴权路径可用（后端已支持从 Bearer 剥取 token）。
 *   2) SSE 帧：OpenAI 帧是**无 event 名的裸 data 行**，不像 chat.ts 那样有
 *      `event:` 前缀；按 `\n` 切分后只处理 `data:` 前缀，遇 `[DONE]` 立即
 *      收尾且不 JSON.parse（parse 会抛异常）；正文增量在 delta.content，
 *      可能为 undefined（首帧只有 role、收尾帧 delta 为空）。
 *
 * 错误处理：response.ok=false 时优先读 `{error:{message,code}}` 结构把
 * error.message 透出，401/400 能显示后端给的原因而非干巴巴的状态码。
 */

import { getApiKey, getTenant } from '../request';

/** OpenAI 兼容 /chat/completions 消息项 */
export interface OaMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

/** OpenAI 兼容 usage（usage 字段非必返，省略时为 undefined） */
export interface OaUsage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

/** 非流式 completion 响应（取第一条 choice.content） */
export interface OaCompletionResult {
  id: string;
  model: string;
  content: string;
  usage?: OaUsage;
}

/** 流式调用控制句柄：与 chat.ts 的 StreamCall 同形（promise + stop），保持团队约定一致 */
export interface OaStreamCall {
  promise: Promise<void>;
  stop: () => void;
}

/** 共享头：Bearer + X-API-Key + X-Tenant-Id */
function buildHeaders(): Record<string, string> {
  const apiKey = getApiKey();
  const tenant = getTenant();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (apiKey) {
    headers['Authorization'] = `Bearer ${apiKey}`;
    headers['X-API-Key'] = apiKey;
  }
  if (tenant) headers['X-Tenant-Id'] = tenant;
  return headers;
}

/**
 * 从后端 error body 中抽 message：
 * - 兼容 OpenAI 标准 `{error:{message,code}}`
 * - 兼容本框架 `{message,code}`（request.ts 内部形态）
 * - 都失败则回落 null
 */
function readErrorMessage(body: any): string | null {
  if (!body) return null;
  if (body.error?.message) return String(body.error.message);
  if (body.message) return String(body.message);
  if (body.msg) return String(body.msg);
  return null;
}

/**
 * 非流式调用 /v1/chat/completions
 * - 把 messages / model / stream=false 序列化进 body
 * - 抽取第一条 choice.message.content + usage
 * - 失败时 throw，message 优先取后端 error.message
 */
export async function callOpenAiCompletions(
  messages: OaMessage[],
  model: string,
  stream: boolean = false,
): Promise<OaCompletionResult> {
  const r = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: buildHeaders(),
    body: JSON.stringify({ messages, model, stream }),
  });
  if (!r.ok) {
    let body: any = null;
    try {
      body = await r.json();
    } catch {
      /* not json */
    }
    const msg = readErrorMessage(body) ?? `HTTP ${r.status}`;
    throw new Error(msg);
  }
  const data = (await r.json()) as {
    id?: string;
    model?: string;
    choices?: { message?: { content?: string } }[];
    usage?: OaUsage;
  };
  const content = data.choices?.[0]?.message?.content ?? '';
  return {
    id: data.id ?? '',
    model: data.model ?? model,
    content,
    usage: data.usage,
  };
}

/**
 * 流式调用 /v1/chat/completions（SSE）
 *
 * @param onChunk 每帧增量文本（已跳过 undefined 与 [DONE]）
 * @param onDone 流正常结束时调用一次（无参；调用方自行维护累计文本）
 * @param onError 出错时调用一次（用户 stop() 不算错，仅静默 resolve）
 * @returns { promise, stop }：与 chat.ts StreamCall 同形；stop() 立刻中止 fetch
 */
export function streamOpenAiCompletions(
  messages: OaMessage[],
  model: string,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (msg: string) => void,
): OaStreamCall {
  const ctl = new AbortController();

  const promise = new Promise<void>((resolve) => {
    let settled = false;
    const finish = (ok: boolean, msg?: string) => {
      if (settled) return;
      settled = true;
      if (ok) onDone();
      else onError(msg ?? 'Unknown error');
      resolve();
    };

    fetch('/v1/chat/completions', {
      method: 'POST',
      headers: buildHeaders(),
      body: JSON.stringify({ messages, model, stream: true }),
      signal: ctl.signal,
    })
      .then(async (r) => {
        if (!r.ok || !r.body) {
          let body: any = null;
          try {
            body = await r.json();
          } catch {
            /* not json */
          }
          finish(false, readErrorMessage(body) ?? `HTTP ${r.status}`);
          return;
        }
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          // OpenAI 帧：只有 data: 行，没有 event:。按 \n 切分后逐行处理 data: 前缀。
          const lines = buf.split('\n');
          buf = lines.pop() ?? '';
          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed.startsWith('data:')) continue;
            const payload = trimmed.slice(5).trim();
            if (!payload) continue;
            if (payload === '[DONE]') {
              // 收尾哨兵：不要 JSON.parse（会抛），直接结束
              reader.cancel().catch(() => {});
              finish(true);
              return;
            }
            let p: any = null;
            try {
              p = JSON.parse(payload);
            } catch {
              // 帧格式异常 → 跳过本帧，不打断整流
              continue;
            }
            // 增量：首帧只有 role，收尾帧 delta 为空，都不产生 content
            const delta = p?.choices?.[0]?.delta?.content;
            if (typeof delta === 'string' && delta.length > 0) {
              onChunk(delta);
            }
          }
        }
        // 流自然关闭但没收到 [DONE]（服务端提前断开）→ 按完成处理
        finish(true);
      })
      .catch((e) => {
        if (settled) return;
        if (e instanceof DOMException && e.name === 'AbortError') {
          // 用户主动停止：静默 resolve，不报错
          settled = true;
          resolve();
          return;
        }
        finish(false, e instanceof Error ? e.message : String(e));
      });
  });

  return { promise, stop: () => ctl.abort() };
}

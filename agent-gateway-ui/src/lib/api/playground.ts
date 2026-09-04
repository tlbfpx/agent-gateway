/**
 * lib/api/playground.ts
 *
 * Prompt Playground — 调一次单模型 SSE 流（不带会话）。
 *
 * 后端契约：复用 `POST /v1/chat/stream`
 *   body 扩展字段（Playground 客户端多带，后端是否消费不在本 Round 范围）：
 *     - system:       string  — system prompt
 *     - temperature:  number  — 0-2
 *     - topP:         number  — 0-1
 *     - maxTokens:    number  — 256-32000
 *   mock 现状只读 prompt，扩展字段对测试无影响。
 *
 * SSE 事件：与 Chat 同 schema
 *   event: chunk     → { content }
 *   event: done      → { response, meta: { model, tokensIn, tokensOut, cacheHit?, finishReason? } }
 *   event: error     → { message }
 *
 * 与 streamChat 的差异：
 *   - 无 sessionId（一次性请求）
 *   - onChunk 返回 { delta, acc }，便于左右 pane 独立维护 acc
 *   - onDone 返回 latencyMs（前端 performance.now() 算）
 */

export interface PlaygroundParams {
  model: string;
  system: string;
  prompt: string;
  temperature: number;
  topP: number;
  maxTokens: number;
}

export interface PlaygroundMeta {
  model?: string;
  tokensIn?: number;
  tokensOut?: number;
  cacheHit?: boolean;
  finishReason?: string;
  /** 前端算的端到端延迟（毫秒），从 fetch 起到 done 事件 */
  latencyMs?: number;
}

export interface PlaygroundCallbacks {
  onChunk: (delta: string, acc: string) => void;
  onDone: (full: string, meta: PlaygroundMeta) => void;
  onError: (msg: string) => void;
  onStatus?: (s: 'started' | 'streaming' | 'done' | 'error' | 'stopped') => void;
}

export interface PlaygroundStreamCall {
  promise: Promise<void>;
  stop: () => void;
}

export function runPlayground(
  params: PlaygroundParams,
  cb: PlaygroundCallbacks,
): PlaygroundStreamCall {
  const ctl = new AbortController();
  const t0 = (typeof performance !== 'undefined' ? performance.now() : Date.now());

  const promise = new Promise<void>((resolve) => {
    let settled = false;
    const finishOk = (full: string, meta: PlaygroundMeta) => {
      if (settled) return;
      settled = true;
      cb.onDone(full, { ...meta, latencyMs: meta.latencyMs ?? Math.round(now() - t0) });
      cb.onStatus?.('done');
      resolve();
    };
    const finishErr = (msg: string) => {
      if (settled) return;
      settled = true;
      cb.onError(msg);
      cb.onStatus?.('error');
      resolve();
    };

    const apiKey = localStorage.getItem('agent-gateway.apiKey') ?? '';
    const tenant = localStorage.getItem('agent-gateway.tenant') ?? 'primary';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (apiKey) headers['X-API-Key'] = apiKey;
    if (tenant) headers['X-Tenant-Id'] = tenant;

    fetch('/v1/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        sessionId: null,
        model: params.model,
        prompt: params.prompt,
        system: params.system,
        temperature: params.temperature,
        topP: params.topP,
        maxTokens: params.maxTokens,
      }),
      signal: ctl.signal,
    })
      .then(async (r) => {
        if (!r.ok || !r.body) {
          finishErr(`HTTP ${r.status}`);
          return;
        }
        cb.onStatus?.('started');
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        let acc = '';
        let evt = '';
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          const lines = buf.split('\n');
          buf = lines.pop() ?? '';
          for (const line of lines) {
            if (line.startsWith('event:')) {
              evt = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              const data = line.slice(5).trim();
              let p: any = null;
              try { p = JSON.parse(data); } catch { /* ignore */ }
              if (!p) continue;
              if (evt === 'chunk' && p.content) {
                acc += p.content;
                cb.onChunk(p.content, acc);
                cb.onStatus?.('streaming');
              } else if (evt === 'done') {
                finishOk(p.response ?? acc, p.meta ?? {});
                reader.cancel().catch(() => {});
                return;
              } else if (evt === 'error') {
                finishErr(p.message ?? 'error');
                reader.cancel().catch(() => {});
                return;
              }
            }
          }
        }
        // 自然断开但无 done/error：按完成处理已收到的部分
        finishOk(acc, {});
      })
      .catch((e) => {
        if (settled) return;
        if (e instanceof DOMException && e.name === 'AbortError') {
          cb.onStatus?.('stopped');
          // 用户主动停止：resolve 而不报 error（与 Chat.tsx 行为一致）
          settled = true;
          resolve();
          return;
        }
        finishErr(String(e));
      });
  });

  return { promise, stop: () => ctl.abort() };
}

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

/* ────────────────────────────────────────────────────────────────────────────
 * 模板持久化（localStorage）
 * ──────────────────────────────────────────────────────────────────────────── */

const TMPL_KEY = 'agent-gateway.playground.templates';

export interface PlaygroundTemplate {
  id: string;
  name: string;
  system: string;
  user: string;
  temperature: number;
  topP: number;
  maxTokens: number;
  model: string;
  createdAt: string;
}

export function listTemplates(): PlaygroundTemplate[] {
  try {
    const raw = localStorage.getItem(TMPL_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? (arr as PlaygroundTemplate[]) : [];
  } catch {
    return [];
  }
}

export function saveTemplate(
  t: Omit<PlaygroundTemplate, 'id' | 'createdAt'>,
): PlaygroundTemplate {
  const all = listTemplates();
  const created: PlaygroundTemplate = {
    ...t,
    id: `tpl_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`,
    createdAt: new Date().toISOString(),
  };
  all.unshift(created);
  try {
    localStorage.setItem(TMPL_KEY, JSON.stringify(all));
  } catch {
    /* quota / disabled storage */
  }
  return created;
}

export function deleteTemplate(id: string): void {
  const all = listTemplates().filter((t) => t.id !== id);
  try { localStorage.setItem(TMPL_KEY, JSON.stringify(all)); } catch { /* ignore */ }
}
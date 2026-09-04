import { http } from '../request';

export interface Session {
  /** 后端字段名为 sessionId（不是 id）— 与 createSession 的返回值一致 */
  sessionId: string;
  lastActiveAt: string;
  /** 首条用户消息摘要（后端生成）；旧后端可能缺省 */
  title?: string;
  /** 会话绑定模型 */
  model?: string;
  /** 历史消息数 */
  messageCount?: number;
}

export const listSessions = () => http.get<Session[]>('/sessions?offset=0&limit=50');
export const createSession = (model: string) =>
  http.post<{ sessionId: string }>(`/sessions?model=${encodeURIComponent(model)}`);
export const getMessages = (id: string) =>
  http.get<unknown[]>(`/sessions/${id}/messages?offset=0&limit=200`);
export const deleteSession = (id: string) =>
  http.delete<{ ok: boolean }>(`/sessions/${encodeURIComponent(id)}`);

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  type?: string;
}

/**
 * 会话标题摘要（与后端 SessionApiController.titleOf 同规则）：
 * 去行首 markdown 标记/反引号 + 折叠空白 + 超 20 字截断。
 * 用于侧栏乐观标题（发送首条消息立即可见，不等后端刷新）。
 */
export function summarizeTitle(content: string): string {
  const flat = content
    .replace(/^[#>*\-`\s]+/, '')
    .replace(/`/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!flat) return '新对话';
  return flat.length > 20 ? flat.slice(0, 20) + '…' : flat;
}

/** 流式调用控制句柄：stop() 中止流；done 在流结束后 resolve（含用户停止/出错） */
export interface StreamCall {
  promise: Promise<void>;
  stop: () => void;
}

/** 消息级用量元数据（done 事件携带）：实际命中模型 + token 估算 */
export interface ChatMeta {
  model: string;
  tokensIn: number;
  tokensOut: number;
  /** 提示缓存命中标记（gateway.llm.prompt-cache，后端契约：done 事件 meta.cacheHit） */
  cacheHit?: boolean;
}

/**
 * 流式对话（SSE）。
 *
 * 返回 { promise, stop }：promise 在 done / error / 网络中断 / 用户 stop() 时 resolve；
 * stop() 立刻中止 fetch。onDone/onError 互斥，至多触发一次。
 * - onDone(full, stopped)：stopped=true 表示用户主动停止（此时 full 为已累积文本，由调用方 state 维护）
 */
export function streamChat(
  sessionId: string | null,
  prompt: string,
  model: string | null,
  onChunk: (t: string) => void,
  onToolCall: (a: string, phase: 'started' | 'result', success?: boolean) => void,
  onDone: (full: string, stopped: boolean, meta?: ChatMeta) => void,
  onError: (msg: string) => void,
  onStatus?: (s: 'started' | 'streaming' | 'done' | 'error' | 'stopped') => void,
): StreamCall {
  const ctl = new AbortController();

  const promise = new Promise<void>((resolve) => {
    let settled = false;
    const finish = (ok: boolean, stopped: boolean, full?: string, msg?: string, meta?: ChatMeta) => {
      if (settled) return;
      settled = true;
      if (ok) {
        onDone(full ?? '', stopped, meta);
        onStatus?.(stopped ? 'stopped' : 'done');
      } else {
        onError(msg ?? 'Unknown error');
        onStatus?.('error');
      }
      resolve();
    };

    const apiKey = localStorage.getItem('agent-gateway.apiKey') ?? '';
    const tenant = localStorage.getItem('agent-gateway.tenant') ?? 'primary';
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (apiKey) headers['X-API-Key'] = apiKey;
    if (tenant) headers['X-Tenant-Id'] = tenant;

    fetch('/v1/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify({ sessionId, prompt, model }),
      signal: ctl.signal,
    })
      .then(async (r) => {
        if (!r.ok || !r.body) {
          onError(`HTTP ${r.status}`);
          onStatus?.('error');
          return resolve();
        }
        onStatus?.('started');
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        let full = '';
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
              try {
                p = JSON.parse(data);
              } catch {
                /* ignore */
              }
              if (!p) continue;
              if (evt === 'chunk' && p.content) {
                full += p.content;
                onChunk(p.content);
                onStatus?.('streaming');
              } else if (evt === 'tool_call_started' && p.agent) onToolCall(p.agent, 'started');
              else if (evt === 'tool_call_result' && p.agent) onToolCall(p.agent, 'result', p.success);
              else if (evt === 'done') {
                finish(true, false, p.response ?? full, undefined, p.meta ?? undefined);
                reader.cancel().catch(() => {});
              } else if (evt === 'error') {
                finish(false, false, undefined, p.message ?? 'error');
                reader.cancel().catch(() => {});
              }
            }
          }
        }
        // 流自然关闭但没收到 done/error（网络中断/服务端提前断开）→ 按完成处理已收到的部分
        finish(true, false, full);
      })
      .catch((e) => {
        if (settled) return;
        if (e instanceof DOMException && e.name === 'AbortError') {
          // 用户主动停止：full 由调用方 state 维护（onChunk 已同步过），传 '' 仅表语义
          finish(true, true, '');
          return;
        }
        onError(String(e));
        onStatus?.('error');
        resolve();
      });
  });

  return { promise, stop: () => ctl.abort() };
}

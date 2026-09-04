const API = '/v1';
const KEY = 'agent-gateway.apiKey';

export const getApiKey = (): string => localStorage.getItem(KEY) ?? '';
export const setApiKey = (v: string) => { v ? localStorage.setItem(KEY, v) : localStorage.removeItem(KEY); };

const H = (extra: Record<string, string> = {}): HeadersInit => ({
  'Content-Type': 'application/json',
  'X-API-Key': getApiKey(),
  ...extra,
});

async function safe<T>(fn: () => Promise<Response>, parser: (r: Response) => Promise<T>, fallback: T): Promise<T> {
  try {
    const r = await fn();
    if (!r.ok) return fallback;
    return await parser(r);
  } catch { return fallback; }
}

const arr = <T,>(d: unknown): T[] => Array.isArray(d) ? (d as T[]) : [];

export interface Session { id: string; lastActiveAt: string; }
export interface ModelInfo { modelId: string; displayName?: string; provider?: string; }
export interface AgentInfo { name: string; description: string; skills: string[]; version?: string; available?: boolean; }
export interface Msg { role: 'user' | 'assistant'; content: string; type?: string; }

export const listSessions = () => safe<Session[]>(
  () => fetch(`${API}/sessions?offset=0&limit=50`, { headers: H() }),
  r => r.json().then(arr<Session>),
  []);

export const createSession = (model: string) => safe<{ sessionId?: string }>(
  () => fetch(`${API}/sessions?model=${encodeURIComponent(model)}`, { method: 'POST', headers: H() }),
  r => r.json(),
  { sessionId: '' },
);

export const getMessages = (id: string) =>
  fetch(`${API}/sessions/${id}/messages?offset=0&limit=100`, { headers: H() })
    .then(r => r.ok ? r.json() : []).then(arr<unknown>);

export const listModels = () => safe<ModelInfo[]>(
  () => fetch(`${API}/models`, { headers: H() }),
  r => r.json().then(arr<ModelInfo>),
  []);

export const listAgents = () => safe<AgentInfo[]>(
  () => fetch(`${API}/agents`, { headers: H() }),
  r => r.json().then(arr<AgentInfo>),
  []);

export const streamChat = (
  sessionId: string | null,
  prompt: string,
  model: string | null,
  onChunk: (t: string) => void,
  onToolCall: (a: string, phase: 'started' | 'result', success?: boolean) => void,
  onDone: (full: string) => void,
  onError: (msg: string) => void,
  onStatus?: (s: 'started' | 'streaming' | 'done' | 'error') => void,
): Promise<void> => new Promise<void>((resolve) => {
  let settled = false;
  const finish = (ok: boolean, body?: { full?: string; msg?: string }) => {
    if (settled) return; settled = true;
    if (ok) onDone(body?.full ?? '');
    else onError(body?.msg ?? 'Unknown error');
    resolve();
  };
  fetch(`${API}/chat/stream`, {
    method: 'POST',
    headers: H(),
    body: JSON.stringify({ sessionId, prompt, model }),
  }).then(async (r) => {
    if (!r.ok || !r.body) { onError(`HTTP ${r.status}`); return resolve(); }
    onStatus?.('started');
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = ''; let full = '';
    let evt = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop() ?? '';
      for (const line of lines) {
        if (line.startsWith('event:')) { evt = line.slice(6).trim(); }
        else if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          let p: any = null;
          try { p = JSON.parse(data); } catch { /* ignore */ }
          if (!p) continue;
          if (evt === 'chunk' && p.content) { full += p.content; onChunk(p.content); onStatus?.('streaming'); }
          else if (evt === 'tool_call_started' && p.agent) onToolCall(p.agent, 'started');
          else if (evt === 'tool_call_result' && p.agent) onToolCall(p.agent, 'result', p.success);
          else if (evt === 'done') { onDone(p.response ?? full); onStatus?.('done'); settled = true; resolve(); }
          else if (evt === 'error') { onError(p.message ?? 'error'); onStatus?.('error'); settled = true; resolve(); }
        }
      }
    }
  }).catch(e => { onError(String(e)); resolve(); });
});

// ─── 管理端：模型 CRUD（/v1/admin/models）───

export interface AdminModel {
  id: string;
  provider: string;
  displayName: string;
  endpoint: string;
  apiKeyMasked: string;
  capabilities: string[];
  contextWindow: number;
  enabled: boolean;
  modelName: string;
}

export const adminListModels = () => safe<AdminModel[]>(
  () => fetch(`${API}/admin/models`, { headers: H() }),
  r => r.json().then(arr<AdminModel>),
  []);

export const adminCreateModel = (m: Partial<AdminModel> & { apiKey: string }) =>
  fetch(`${API}/admin/models`, {
    method: 'POST', headers: H(),
    body: JSON.stringify(m),
  }).then(r => r.ok ? r.json() : r.json().then(e => Promise.reject(e)));

export const adminUpdateModel = (id: string, m: Partial<AdminModel> & { apiKey?: string }) =>
  fetch(`${API}/admin/models/${encodeURIComponent(id)}`, {
    method: 'PUT', headers: H(),
    body: JSON.stringify(m),
  }).then(r => r.ok ? r.json() : r.json().then(e => Promise.reject(e)));

export const adminDeleteModel = (id: string) =>
  fetch(`${API}/admin/models/${encodeURIComponent(id)}`, {
    method: 'DELETE', headers: H(),
  }).then(r => r.ok ? { deleted: id } : r.json().then(e => Promise.reject(e)));

// ─── 管理端：Webhook 订阅（/v1/admin/webhooks）───
export interface WebhookSub { url: string; events: string[] }
export interface DeadLetter { url: string; event: string; attempts: number; error: string }

export const listWebhooks = () => safe<WebhookSub[]>(
  () => fetch(`${API}/admin/webhooks`, { headers: H() }),
  r => r.json().then(arr<WebhookSub>), []);

export const subscribeWebhook = (url: string, secret: string, events: string[]) =>
  fetch(`${API}/admin/webhooks`, { method: 'POST', headers: H(), body: JSON.stringify({ url, secret, events }) })
    .then(r => r.json());

export const unsubscribeWebhook = (url: string) =>
  fetch(`${API}/admin/webhooks?url=${encodeURIComponent(url)}`, { method: 'DELETE', headers: H() })
    .then(r => r.json());

export const listDeadLetters = () => safe<DeadLetter[]>(
  () => fetch(`${API}/admin/webhooks/dead-letters`, { headers: H() }),
  r => r.json().then(arr<DeadLetter>), []);

// ─── 管理端：审计日志（/v1/admin/audit/logs）───
export interface AuditEntry { eventId: string; actor: string; type: string; time: string; resource: string; action: string; result: string; detail: string }
export const listAuditLogs = (tenant: string) => safe<AuditEntry[]>(
  () => fetch(`${API}/admin/audit/logs?tenant=${encodeURIComponent(tenant)}&limit=50`, { headers: H() }),
  r => r.json().then(arr<AuditEntry>), []);

// ─── 管理端：配置版本（/v1/admin/config/{name}/versions）───
export interface ConfigVer { version: string; at: string; size: number }
export const listConfigVersions = (name: 'models' | 'api-keys') => safe<ConfigVer[]>(
  () => fetch(`${API}/admin/config/${name}/versions`, { headers: H() }),
  r => r.json().then(arr<ConfigVer>), []);

export const rollbackConfig = (name: 'models' | 'api-keys', version: string) =>
  fetch(`${API}/admin/config/${name}/rollback?version=${encodeURIComponent(version)}`, { method: 'POST', headers: H() })
    .then(r => r.json());

export const configDiff = (name: 'models' | 'api-keys', from: string, to: string) =>
  fetch(`${API}/admin/config/${name}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { headers: H() })
    .then(r => r.json())
    .then((d: { fields: Record<string, [string | null, string | null]> }) => d.fields);

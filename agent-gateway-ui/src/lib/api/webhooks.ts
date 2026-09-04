import { http } from '../request';

export interface WebhookSub {
  url: string;
  events: string[];
  secret?: string;
  active?: boolean;
  createdAt?: string;
}

export interface DeadLetter {
  url: string;
  event: string;
  attempts: number;
  error: string;
  lastTryAt: string;
}

export interface WebhookHistoryRow {
  id: string;
  url: string;
  event: string;
  status: 'success' | 'failed';
  attempts: number;
  latencyMs: number;
  time: string;
  /** 实际投递内容（mock 时回显 payload） */
  payload?: string;
}

export interface WebhookTestResult {
  ok: boolean;
  /** HTTP 状态码 */
  status?: number;
  latencyMs: number;
  /** 远端响应摘要 */
  response?: string;
}

export const listWebhooks = () => http.get<WebhookSub[]>('/admin/webhooks');
export const subscribeWebhook = (url: string, secret: string, events: string[]) =>
  http.post<WebhookSub>('/admin/webhooks', { url, secret, events });
export const unsubscribeWebhook = (url: string) =>
  http.delete<{ ok: boolean }>(`/admin/webhooks?url=${encodeURIComponent(url)}`);
export const listDeadLetters = () =>
  http.get<DeadLetter[]>('/admin/webhooks/dead-letters');

export const listWebhookHistory = (range = '24h') =>
  http.get<WebhookHistoryRow[]>(`/admin/webhooks/history?range=${range}`).catch(() => []);

export const sendTestEvent = (url: string, event: string) =>
  http.post<WebhookTestResult>(`/admin/webhooks/test`, { url, event });

/**
 * 重新投递一条死信（运营#12，运营评审反复提到的运营断点）
 * 后端 POST /admin/webhooks/dead-letters/redeliver 接收 {url, event}，
 * 返回 {ok, attempts, error?}。
 */
export interface RedeliverResult {
  ok: boolean;
  attempts: number;
  error?: string;
}
export const redeliverDeadLetter = (url: string, event: string) =>
  http.post<RedeliverResult>('/admin/webhooks/dead-letters/redeliver', { url, event });
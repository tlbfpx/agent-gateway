import { http, ApiError } from '../request';

// Re-export for components that previously imported getApiKey/setApiKey from lib/api
export { getApiKey, setApiKey, clearAuth } from '../request';

export interface ApiKey {
  id: string;
  owner: string;
  tenant: string;
  enabled: boolean;
  models?: string[];
  rateLimitRpm?: number;
  expiresAt?: string;
  createdAt: string;
  lastUsedAt?: string;
  /** Round6：虚拟 Key 余额（mock 默认 0） */
  balanceCny?: number;
  /** Round6：月度配额 CNY（mock 默认 1000） */
  monthlyQuotaCny?: number;
}

/**
 * VirtualKey — 计费用的预付费虚拟 Key（与 ApiKey 不同的实体）
 * MVP 字段与后端 AdminVirtualKeyController 返回对齐
 */
export interface VirtualKey {
  vkId: string;
  owner: string;
  tenant: string;
  label: string;
  monthlyQuotaCny: number;
  balanceCny: number;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
}

/**
 * TopUpResult — Stripe 充值结账会话返回
 */
export interface TopUpResult {
  checkoutUrl: string;
  amountCny: number;
  sessionId: string;
}

export const listApiKeys = () => http.get<ApiKey[]>('/admin/api-keys');
export const createApiKey = (body: Partial<ApiKey> & { apiKey?: string }) =>
  http.post<{ apiKey: ApiKey & { value: string }; created: ApiKey & { value: string } }>(
    '/admin/api-keys',
    body,
  );
export const deleteApiKey = (id: string) => http.delete(`/admin/api-keys/${encodeURIComponent(id)}`);

/**
 * 虚拟 Key 充值 —— 调用后端创建 Stripe Checkout Session
 * 返回 checkoutUrl 让前端引导用户跳转支付
 */
export const topUpVirtualKey = (vkId: string, amountCny: number) =>
  http.post<TopUpResult>(`/admin/virtual-keys/${encodeURIComponent(vkId)}/topup`, { amountCny });

/**
 * 虚拟 Key 用量明细 —— MVP 复用 billing.ts 的 UsageRecord
 * 后续可改为 tenant-level reconcile endpoint
 */
export type { UsageRecord } from './billing';
import type { UsageRecord } from './billing';
export const getVirtualKeyUsage = (
  vkId: string,
  params: { from?: string; to?: string } = {},
) => {
  const q = new URLSearchParams();
  if (params.from) q.set('from', params.from);
  if (params.to) q.set('to', params.to);
  const qs = q.toString();
  const path = `/admin/virtual-keys/${encodeURIComponent(vkId)}/usage${qs ? `?${qs}` : ''}`;
  return http.get<UsageRecord[]>(path);
};

export { ApiError };
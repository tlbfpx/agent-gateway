/**
 * plugins.ts — 插件系统 API client（Round 15 §wasm-plugins §8）
 */
import { getAdminToken } from './admin';
import { getApiKey } from '../request';

export type PluginFormat = 'JAVA' | 'WASM';
export type PluginCapability =
  | 'HEADER_INJECT' | 'BODY_TRANSFORM' | 'RATE_LIMIT'
  | 'AUDIT' | 'COMPRESS' | 'LOG';

export interface PluginDescriptor {
  id: string;
  name: string;
  version: string;
  description: string;
  format: PluginFormat;
  capabilities: PluginCapability[];
  tags: string[];
  builtin: boolean;
}

export interface PluginResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  blocked: boolean;
  blockReason?: string;
}

const ADMIN = () => ({ 'X-Admin-Token': getAdminToken(), 'Content-Type': 'application/json' });
const API = () => ({ 'X-API-Key': getApiKey(), 'Content-Type': 'application/json' });

async function req<T>(url: string, init: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${init.method ?? 'GET'} ${url} → ${res.status} ${body}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

export const listPlugins = () =>
  req<PluginDescriptor[]>('/v1/admin/plugins', { method: 'GET', headers: ADMIN() });

export const getPlugin = (id: string) =>
  req<PluginDescriptor>(`/v1/admin/plugins/${encodeURIComponent(id)}`, {
    method: 'GET', headers: ADMIN(),
  });

export const disablePlugin = (id: string) =>
  req<{ disabled: boolean; id: string }>(`/v1/admin/plugins/${encodeURIComponent(id)}/disable`, {
    method: 'POST', headers: ADMIN(),
  });

export const reloadPlugins = () =>
  req<{ reloaded: boolean; total: number }>('/v1/admin/plugins/reload', {
    method: 'POST', headers: ADMIN(),
  });

export const testSandbox = (body: {
  path?: string; method?: string; body?: string; tenant?: string;
  headers?: Record<string, string>;
}) =>
  req<PluginResponse>('/v1/admin/plugins/test', {
    method: 'POST', headers: ADMIN(), body: JSON.stringify(body),
  });

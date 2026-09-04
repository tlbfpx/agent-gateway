/**
 * demo.ts — Demo 模式 API 客户端（spec 2026-09-04 §demo-mode §6）
 *
 * 后端契约：
 *   GET  /v1/demo/status    → { enabled: bool, ttlSeconds: number }
 *   POST /v1/demo/bootstrap → DemoSession（tenantId + apiKey + adminToken + adminEmail + expiresAt）
 *
 * 调用约定：
 *   - status() 不要求鉴权（前端首屏用）
 *   - bootstrap() 不要求鉴权（公开端点）
 *   - 后端 enabled=false 时两个端点都返回 404
 */

import { http } from '../request';

export interface DemoStatus {
  enabled: boolean;
  ttlSeconds: number;
}

export interface DemoSession {
  tenantId: string;
  apiKey: string;
  adminToken: string;
  adminEmail: string;
  expiresAt: string; // ISO-8601
}

export const demoApi = {
  status: () => http.get<DemoStatus>('/demo/status'),
  bootstrap: () => http.post<DemoSession>('/demo/bootstrap'),
};

/** localStorage key 集合（与 lib/request.ts 的 KEY_* 对齐） */
export const DEMO_STORAGE_KEYS = {
  tenant: 'agent-gateway.tenant',
  apiKey: 'agent-gateway.apiKey',
  adminToken: 'agent-gateway.adminToken',
} as const;

/** 把 demo session 写入 localStorage（覆盖现有值）。
 *  同时写入 expiresAt 给 DemoBanner 倒计时用。 */
export function persistDemoSession(session: DemoSession): void {
  window.localStorage.setItem(DEMO_STORAGE_KEYS.tenant, session.tenantId);
  window.localStorage.setItem(DEMO_STORAGE_KEYS.apiKey, session.apiKey);
  window.localStorage.setItem(DEMO_STORAGE_KEYS.adminToken, session.adminToken);
  window.localStorage.setItem('agent-gateway.demoExpiresAt', session.expiresAt);
}
/**
 * signup.ts — 自助注册 API 客户端（spec 2026-09-04 §self-serve-signup §6）
 *
 * 后端契约：
 *   POST /v1/auth/signup  → SignupSession（tenantId + email + adminToken）
 *
 * 错误码：
 *   400 — email/password/companyName 校验失败
 *   409 — email 已被注册
 */

import { http } from '../request';

export interface SignupSession {
  tenantId: string;
  email: string;
  adminToken: string;
}

export const signupApi = {
  signup: (email: string, password: string, companyName: string) =>
    http.post<SignupSession>('/auth/signup', { email, password, companyName }),
};

/** 把 signup 会话写入 localStorage（tenant + adminToken）。
 *  注意：signup 只拿到 adminToken,没有 apiKey（用户后续在 Settings 签发）。 */
export function persistSignupSession(session: SignupSession): void {
  window.localStorage.setItem('agent-gateway.tenant', session.tenantId);
  window.localStorage.removeItem('agent-gateway.apiKey');
  window.localStorage.setItem('agent-gateway.adminToken', session.adminToken);
  window.localStorage.removeItem('agent-gateway.demoExpiresAt');
}
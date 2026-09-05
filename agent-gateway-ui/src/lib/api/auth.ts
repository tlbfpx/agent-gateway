/**
 * auth.ts — Admin 鉴权 API（Round 14 §bcrypt-auth）
 *
 * 与 lib/api/admin.ts 的 getAdminToken() 协同:登录成功后存到 localStorage,
 * 之后所有 admin 接口的 X-Admin-Token header 自动使用。
 */
export interface LoginResponse {
  token: string;
  user: {
    id: number;
    email: string;
    name: string;
    role: string;
    status: string;
    tenantId: string;
    createdAt: string;
    lastLoginAt: string;
  };
}

const TOKEN_KEY = 'agent-gateway.adminToken';

export const setAdminToken = (token: string) => {
  try { localStorage.setItem(TOKEN_KEY, token); } catch { /* ignore */ }
};

export const clearAdminToken = () => {
  try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
};

export const hasAdminToken = (): boolean => {
  try {
    return Boolean(localStorage.getItem(TOKEN_KEY));
  } catch {
    return false;
  }
};

export const login = (tenantId: string, email: string, password: string) =>
  fetch('/v1/admin/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantId, email, password }),
  }).then(async (r) => {
    if (!r.ok) {
      const body = await r.text();
      throw new Error(`login → ${r.status} ${body}`);
    }
    const body = (await r.json()) as LoginResponse;
    setAdminToken(body.token);
    return body;
  });

export const logout = () => {
  const token = getCurrentToken();
  return fetch('/v1/admin/auth/logout', {
    method: 'POST',
    headers: { 'X-Admin-Token': token },
  }).finally(() => clearAdminToken());
};

// ====================== OIDC SSO (Round 3 §sso-oidc) ======================

/** OIDC 状态接口（spec 2026-09-05 §sso-oidc §6.2）。
 *  Frontend 启动时调一次；若 enabled=true 显示「企业 SSO」按钮。 */
export interface OidcStatus {
  enabled: boolean;
  /** 显示文案（多 IdP 时给个品牌名，便于切换） */
  displayName?: string;
}

export const oidcStatus = () =>
  fetch('/v1/auth/oidc/status').then(async (r) => {
    if (r.status === 404) return { enabled: false } as OidcStatus;
    if (!r.ok) throw new Error(`oidc status → ${r.status}`);
    return (await r.json()) as OidcStatus;
  });

/** 触发 OIDC 登录：前端 GET 这个拿到 authorizationUrl，然后 location.href 跳。 */
export interface OidcLoginResponse {
  authorizationUrl: string;
  state: string;
  nonce: string;
  returnTo: string;
}

export const oidcStartLogin = (returnTo = '/', tenant?: string) => {
  const params = new URLSearchParams();
  params.set('returnTo', returnTo);
  if (tenant) params.set('tenant', tenant);
  return fetch(`/v1/auth/oidc/login?${params.toString()}`)
      .then(async (r) => {
        if (!r.ok) {
          const body = await r.text();
          throw new Error(`oidc login → ${r.status} ${body}`);
        }
        return (await r.json()) as OidcLoginResponse;
      });
}

export const getMe = (token: string) =>
  fetch('/v1/admin/auth/me', {
    method: 'POST',
    headers: { 'X-Admin-Token': token },
  }).then((r) => r.json() as Promise<{ role: string }>);

const getCurrentToken = () => {
  try { return localStorage.getItem(TOKEN_KEY) ?? ''; }
  catch { return ''; }
};

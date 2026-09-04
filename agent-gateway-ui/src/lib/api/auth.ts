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

export const getMe = (token: string) =>
  fetch('/v1/admin/auth/me', {
    method: 'POST',
    headers: { 'X-Admin-Token': token },
  }).then((r) => r.json() as Promise<{ role: string }>);

const getCurrentToken = () => {
  try { return localStorage.getItem(TOKEN_KEY) ?? ''; }
  catch { return ''; }
};

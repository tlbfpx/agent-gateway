/**
 * admin.ts — Admin & Team 管理 API（Round 12 §multi-admin §6）
 *
 * 端点列表见 gateway-interfaces/.../AdminAdminController.java
 */

export type AdminRole = 'OWNER' | 'ADMIN' | 'OPERATOR' | 'VIEWER';
export type AdminStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED';

export interface AdminUser {
  id: number;
  email: string;
  name: string;
  role: AdminRole;
  status: AdminStatus;
  tenantId: string;
  createdAt: string;
  lastLoginAt: string;
}

export interface Team {
  id: number;
  name: string;
  tenantId: string;
  ownerId: number;
  memberIds: number[];
  size: number;
  createdAt: string;
}

const HEADERS = (token: string) => ({
  'X-Admin-Token': token,
  'Content-Type': 'application/json',
});

const jsonHeaders = (token: string) => ({
  'X-Admin-Token': token,
  'Content-Type': 'application/json',
});

async function req<T>(url: string, init: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    let body: any = {};
    try { body = await res.json(); } catch { /* noop */ }
    const reason = body?.error || body?.message || res.statusText;
    throw new Error(`${init.method ?? 'GET'} ${url} → ${res.status} ${reason}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

// ============= AdminUser =============

export const registerAdmin = (token: string, body: {
  email: string; name: string; role: AdminRole; tenantId: string;
}) =>
  req<AdminUser>('/v1/admin/admins', {
    method: 'POST',
    headers: HEADERS(token),
    body: JSON.stringify(body),
  });

export const listAdmins = (token: string, query: {
  tenant?: string; role?: AdminRole; status?: AdminStatus;
  limit?: number; offset?: number;
} = {}) => {
  const p = new URLSearchParams();
  p.set('tenant', query.tenant ?? 'au');
  if (query.role) p.set('role', query.role);
  if (query.status) p.set('status', query.status);
  p.set('limit', String(query.limit ?? 50));
  p.set('offset', String(query.offset ?? 0));
  return req<AdminUser[]>(`/v1/admin/admins?${p.toString()}`, {
    method: 'GET',
    headers: jsonHeaders(token),
  });
};

export const getAdmin = (token: string, id: number) =>
  req<AdminUser>(`/v1/admin/admins/${id}`, {
    method: 'GET',
    headers: jsonHeaders(token),
  });

export const changeAdminRole = (token: string, id: number, role: AdminRole) =>
  req<AdminUser>(`/v1/admin/admins/${id}/role`, {
    method: 'PUT',
    headers: jsonHeaders(token),
    body: JSON.stringify({ role }),
  });

export const suspendAdmin = (token: string, id: number) =>
  req<AdminUser>(`/v1/admin/admins/${id}/status`, {
    method: 'PUT',
    headers: jsonHeaders(token),
    body: JSON.stringify({ action: 'suspend' }),
  });

export const activateAdmin = (token: string, id: number) =>
  req<AdminUser>(`/v1/admin/admins/${id}/status`, {
    method: 'PUT',
    headers: jsonHeaders(token),
    body: JSON.stringify({ action: 'activate' }),
  });

export const deleteAdmin = (token: string, id: number) =>
  req<{ deleted: boolean; id: number }>(`/v1/admin/admins/${id}`, {
    method: 'DELETE',
    headers: jsonHeaders(token),
  });

// ============= Team =============

export const createTeam = (token: string, body: {
  name: string; tenantId: string; ownerId: number;
}) =>
  req<Team>('/v1/admin/teams', {
    method: 'POST',
    headers: jsonHeaders(token),
    body: JSON.stringify(body),
  });

export const listTeams = (token: string, tenant: string = 'au') =>
  req<Team[]>(`/v1/admin/teams?tenant=${encodeURIComponent(tenant)}`, {
    method: 'GET',
    headers: jsonHeaders(token),
  });

export const getTeam = (token: string, id: number) =>
  req<Team>(`/v1/admin/teams/${id}`, {
    method: 'GET',
    headers: jsonHeaders(token),
  });

export const addTeamMember = (token: string, teamId: number, memberId: number) =>
  req<Team>(`/v1/admin/teams/${teamId}/members`, {
    method: 'POST',
    headers: jsonHeaders(token),
    body: JSON.stringify({ memberId }),
  });

export const removeTeamMember = (token: string, teamId: number, memberId: number) =>
  req<Team>(`/v1/admin/teams/${teamId}/members/${memberId}`, {
    method: 'DELETE',
    headers: jsonHeaders(token),
  });

export const transferTeamOwnership = (token: string, teamId: number, newOwnerId: number) =>
  req<Team>(`/v1/admin/teams/${teamId}/owner`, {
    method: 'PUT',
    headers: jsonHeaders(token),
    body: JSON.stringify({ newOwnerId }),
  });

// ============= helpers =============

/** 本地取 admin token(R12 用;R13 改为从 auth context 取) */
export function getAdminToken(): string {
  try {
    return localStorage.getItem('agent-gateway.adminToken') ?? '';
  } catch {
    return '';
  }
}

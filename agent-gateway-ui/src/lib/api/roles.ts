/**
 * roles.ts — D1 角色管理 / 用户绑定 / 策略预览 API（spec §GW-RBAC-011）
 * 对齐后端：/v1/admin/roles、/v1/admin/users/{id}/roles、/v1/admin/rbac/preview
 */
import { request } from '../request';

export interface RbacPermission {
  // sealed Permission JSON 形态（按 kind 多态）
  agentName?: string;
  allowedSkills?: string[];
  models?: string[];
  skillName?: string;
}

export interface RbacRole {
  id: { value: string };
  name: string;
  description: string;
  permissions: RbacPermission[];
}

export interface PolicyPreviewResult {
  user: { value: string };
  tenant: { value: string };
  allowedAgents: string[];
  allowedModels: { value: string }[];
}

/** 归一化：后端 ModelPermission 序列化为 [{value}] 对象数组，UI 统一为 string[]。 */
function normalizeRole(r: any): RbacRole {
  return {
    ...r,
    permissions: (r.permissions ?? []).map((p: any) => ({
      ...p,
      models: Array.isArray(p.models)
        ? p.models.map((m: any) => (typeof m === 'string' ? m : m?.value)).filter(Boolean)
        : undefined,
    })),
  };
}

/** record 反序列化：Java record 直接平铺为 JSON 对象（models 归一化）。 */
export async function listRoles(): Promise<RbacRole[]> {
  const rows = await request<any[]>('/admin/roles');
  return rows.map(normalizeRole);
}

export async function createRole(body: {
  name: string;
  description?: string;
  permissions: RbacPermission[];
}): Promise<RbacRole> {
  return request<RbacRole>('/admin/roles', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function updateRole(
  id: string,
  body: { name: string; description?: string; permissions: RbacPermission[] },
): Promise<RbacRole> {
  return request<RbacRole>(`/admin/roles/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export async function deleteRole(id: string): Promise<void> {
  await request<void>(`/admin/roles/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export async function listUserRoles(userId: string): Promise<RbacRole[]> {
  const rows = await request<any[]>(`/admin/users/${encodeURIComponent(userId)}/roles`);
  return rows.map(normalizeRole);
}

export async function bindRole(userId: string, roleId: string): Promise<void> {
  await request<void>(`/admin/users/${encodeURIComponent(userId)}/roles`, {
    method: 'POST',
    body: JSON.stringify({ roleId }),
  });
}

export async function unbindRole(userId: string, roleId: string): Promise<void> {
  await request<void>(
    `/admin/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(roleId)}`,
    { method: 'DELETE' },
  );
}

export async function previewPolicy(userId: string, tenantId?: string): Promise<PolicyPreviewResult> {
  return request<PolicyPreviewResult>('/admin/rbac/preview', {
    method: 'POST',
    body: JSON.stringify({ userId, tenantId }),
  });
}

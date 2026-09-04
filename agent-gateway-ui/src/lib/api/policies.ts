/**
 * policies.ts — 策略中心（RBAC 规则 CRUD）
 *
 * 后端契约（待 bootstrap 接线）：
 *   GET    /v1/admin/rbac/policies             → Policy[]
 *   POST   /v1/admin/rbac/policies             → Policy
 *   PUT    /v1/admin/rbac/policies/:id        → Policy
 *   DELETE /v1/admin/rbac/policies/:id        → { deleted }
 *   POST   /v1/admin/rbac/policies/:id/enable → Policy
 *
 * 降级策略：接口不可达时使用本地 mock 规则，保证 UI 可用。
 */
import { http } from '../request';

export interface Policy {
  id: string;
  name: string;
  /** 优先级（数字越大越先匹配） */
  priority: number;
  /** 规则主体：actor / role / tenant */
  subject: { kind: 'actor' | 'role' | 'tenant'; value: string };
  /** 资源匹配 */
  resource: { kind: 'model' | 'agent' | 'skill' | '*'; pattern: string };
  /** 动作 */
  action: 'read' | 'write' | 'invoke' | 'admin' | '*';
  /** 决定 */
  effect: 'allow' | 'deny';
  /** 备注 */
  description?: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface PolicyInput {
  name: string;
  priority: number;
  subject: Policy['subject'];
  resource: Policy['resource'];
  action: Policy['action'];
  effect: Policy['effect'];
  description?: string;
  enabled: boolean;
}

const SEED: Policy[] = [
  {
    id: 'p-001',
    name: 'admin 完整权限',
    priority: 1000,
    subject: { kind: 'role', value: 'admin' },
    resource: { kind: '*', pattern: '*' },
    action: '*',
    effect: 'allow',
    description: '管理员对所有资源的所有动作',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p-002',
    name: 'ops 读权限',
    priority: 500,
    subject: { kind: 'role', value: 'ops' },
    resource: { kind: '*', pattern: '*' },
    action: 'read',
    effect: 'allow',
    description: '运维只读所有资源',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p-003',
    name: '跨租户隔离',
    priority: 900,
    subject: { kind: 'tenant', value: '*' },
    resource: { kind: '*', pattern: '*' },
    action: 'invoke',
    effect: 'deny',
    description: '默认拒绝跨租户调用，需在白名单中显式 allow',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p-004',
    name: 'developer 模型调用',
    priority: 400,
    subject: { kind: 'role', value: 'developer' },
    resource: { kind: 'model', pattern: 'gpt-4o|claude-3.7' },
    action: 'invoke',
    effect: 'allow',
    description: '开发者可调用白名单模型',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p-005',
    name: '敏感 Skill 隔离',
    priority: 800,
    subject: { kind: 'role', value: '*' },
    resource: { kind: 'skill', pattern: 'admin-*' },
    action: '*',
    effect: 'deny',
    description: '禁止所有角色调用 admin- 前缀的敏感 Skill',
    enabled: false,
    createdAt: '2026-01-01T00:00:00Z',
  },
];

async function fetchPolicies(): Promise<Policy[]> {
  try {
    const raw = await http.get<unknown[]>('/admin/rbac/policies');
    // legacy 端点返回 Role[]（D1 迁移兼容）——识别并映射为 Policy 视图，避免 subject.kind undefined crash
    const isPolicy = (x: any) => x && x.subject && x.subject.kind && x.effect;
    const asRole = (x: any) => x && x.permissions !== undefined && x.name;
    if (raw.every(isPolicy)) return raw as Policy[];
    if (raw.some(asRole)) {
      // Role → Policy 视图：每个角色一条 allow 规则（资源按权限面聚合展示）
      return raw.filter(asRole).map((r: any, i: number) => ({
        id: r.id ?? `role-${i}`,
        name: `角色策略 · ${r.name}`,
        priority: 100 + i,
        subject: { kind: 'role' as const, value: r.name },
        resource: { kind: '*' as const, pattern: '*' },
        action: '*' as const,
        effect: 'allow' as const,
        description: r.description,
        enabled: true,
      }));
    }
    return SEED;
  } catch {
    return SEED;
  }
}

async function createPolicy(body: PolicyInput): Promise<Policy> {
  try {
    return await http.post<Policy>('/admin/rbac/policies', body);
  } catch {
    // mock 模式：本地构造
    return {
      id: `p-${Date.now()}`,
      ...body,
      createdAt: new Date().toISOString(),
    };
  }
}

async function updatePolicy(id: string, body: Partial<PolicyInput>): Promise<Policy> {
  try {
    return await http.put<Policy>(`/admin/rbac/policies/${encodeURIComponent(id)}`, body);
  } catch {
    return { id, ...body } as Policy;
  }
}

async function deletePolicy(id: string): Promise<{ deleted: string }> {
  try {
    return await http.delete<{ deleted: string }>(`/admin/rbac/policies/${encodeURIComponent(id)}`);
  } catch {
    return { deleted: id };
  }
}

export const policiesApi = {
  list: fetchPolicies,
  create: createPolicy,
  update: updatePolicy,
  delete: deletePolicy,
};

/** 模拟本地 CRUD 缓存（mock 模式用） */
const localStore: Policy[] = [...SEED];
export function getLocalStore(): Policy[] {
  return localStore;
}
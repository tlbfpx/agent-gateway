/**
 * useRole — 角色权限状态
 *
 * 当前用户角色 + RBAC 规则矩阵（基于 useCommandPalette 的同一份内存 store）：
 *  - 'admin'    完整权限
 *  - 'ops'      只读 + 运营配置
 *  - 'developer' 开发权限（仅对话 + API）
 *  - 'viewer'   只读
 *
 * 后端契约（待 bootstrap 接线）：
 *   GET /v1/me           → { role, permissions[] }
 *
 * 降级：localStorage `agent-gateway.role` 默认 'admin'
 */
import { useEffect, useState, useCallback } from 'react';

export type Role = 'admin' | 'ops' | 'developer' | 'viewer';

const KEY = 'agent-gateway.role';

let _role: Role = readInitial();
const _subs = new Set<(r: Role) => void>();

function readInitial(): Role {
  try {
    const v = localStorage.getItem(KEY);
    if (v === 'admin' || v === 'ops' || v === 'developer' || v === 'viewer') return v;
  } catch {
    /* ignore */
  }
  return 'admin';
}

function commit(r: Role) {
  try {
    localStorage.setItem(KEY, r);
  } catch {
    /* ignore */
  }
  _role = r;
  _subs.forEach((fn) => fn(r));
}

export function setRole(r: Role) {
  commit(r);
}

export function useRole(): Role {
  const [role, setLocal] = useState<Role>(_role);
  useEffect(() => {
    const fn = (r: Role) => setLocal(r);
    _subs.add(fn);
    return () => {
      _subs.delete(fn);
    };
  }, []);
  return role;
}

const ALL_ROLES: Role[] = ['admin', 'ops', 'developer', 'viewer'];

export function listRoles(): Role[] {
  return ALL_ROLES;
}

export const ROLE_LABEL: Record<Role, string> = {
  admin: 'admin · 超级管理员',
  ops: 'ops · 运维',
  developer: 'developer · 开发者',
  viewer: 'viewer · 只读',
};

export const ROLE_COLOR: Record<Role, string> = {
  admin: 'gold',
  ops: 'blue',
  developer: 'purple',
  viewer: 'default',
};

/** 路由 → 允许的角色集合 */
const ROUTE_ROLES: Record<string, Role[]> = {
  '/dashboard': ['admin', 'ops', 'developer', 'viewer'],
  '/health': ['admin', 'ops', 'viewer'],
  '/models': ['admin', 'ops'],
  '/api-keys': ['admin', 'ops'],
  '/discovery': ['admin', 'ops', 'developer', 'viewer'],
  '/agents': ['admin', 'ops'],
  '/cost': ['admin', 'ops'],
  '/ratelimit': ['admin', 'ops'],
  '/alerts': ['admin', 'ops'],
  '/webhooks': ['admin', 'ops'],
  '/audit': ['admin', 'ops', 'viewer'],
  '/config-history': ['admin', 'ops'],
  '/rbac': ['admin'],
  '/policies': ['admin'],
  '/api': ['admin', 'developer'],
  '/chat': ['admin', 'developer', 'viewer'],
  '/settings': ['admin', 'ops', 'developer', 'viewer'],
  '/help': ['admin', 'ops', 'developer', 'viewer'],
};

/** 操作 → 允许的角色集合 */
const ACTION_ROLES: Record<string, Role[]> = {
  // 模型
  'model.create': ['admin'],
  'model.delete': ['admin'],
  'model.edit': ['admin', 'ops'],
  // API Key
  'apikey.create': ['admin', 'ops'],
  'apikey.revoke': ['admin'],
  // Agent
  'agent.register': ['admin'],
  'agent.edit': ['admin', 'ops'],
  'agent.delete': ['admin'],
  // Webhook
  'webhook.subscribe': ['admin'],
  'webhook.delete': ['admin'],
  // 配置
  'config.rollback': ['admin'],
  // RBAC
  'rbac.preview': ['admin', 'ops'],
  'policy.create': ['admin'],
  'policy.edit': ['admin'],
  'policy.delete': ['admin'],
  // 告警
  'alert.create': ['admin', 'ops'],
  'alert.ack': ['admin', 'ops'],
  // Chat
  'chat.send': ['admin', 'developer', 'viewer'],
};

export function canAccessRoute(role: Role, path: string): boolean {
  // 最长前缀匹配
  let best: string | null = null;
  for (const key of Object.keys(ROUTE_ROLES)) {
    if (path === key || path.startsWith(key + '/')) {
      if (best == null || key.length > best.length) best = key;
    }
  }
  if (best == null) return true; // 未知路由放行
  return ROUTE_ROLES[best].includes(role);
}

export function canPerform(role: Role, action: string): boolean {
  const roles = ACTION_ROLES[action];
  if (!roles) return true; // 未声明默认放行
  return roles.includes(role);
}

/** React Hook 形式：判断当前角色能否执行 action */
export function usePermission(action: string): boolean {
  const role = useRole();
  return canPerform(role, action);
}
/**
 * role.test.ts — 角色权限矩阵
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  useRole,
  setRole,
  canAccessRoute,
  canPerform,
  usePermission,
  ROLE_LABEL,
} from '../src/hooks/useRole';

beforeEach(() => {
  localStorage.clear();
  setRole('admin');
});

describe('useRole', () => {
  it('默认 admin', () => {
    const { result } = renderHook(() => useRole());
    expect(result.current).toBe('admin');
  });

  it('setRole 更新状态', () => {
    const { result } = renderHook(() => useRole());
    act(() => setRole('viewer'));
    expect(result.current).toBe('viewer');
  });

  it('localStorage 持久化', () => {
    setRole('ops');
    expect(localStorage.getItem('agent-gateway.role')).toBe('ops');
    const { result } = renderHook(() => useRole());
    expect(result.current).toBe('ops');
  });
});

describe('canAccessRoute', () => {
  it('admin 全开', () => {
    expect(canAccessRoute('admin', '/dashboard')).toBe(true);
    expect(canAccessRoute('admin', '/rbac')).toBe(true);
    expect(canAccessRoute('admin', '/policies')).toBe(true);
    expect(canAccessRoute('admin', '/api')).toBe(true);
  });

  it('viewer 看不到写操作', () => {
    expect(canAccessRoute('viewer', '/dashboard')).toBe(true);
    expect(canAccessRoute('viewer', '/rbac')).toBe(false);
    expect(canAccessRoute('viewer', '/policies')).toBe(false);
    expect(canAccessRoute('viewer', '/cost')).toBe(false);
  });

  it('developer 主要看 chat + api', () => {
    expect(canAccessRoute('developer', '/chat')).toBe(true);
    expect(canAccessRoute('developer', '/api')).toBe(true);
    expect(canAccessRoute('developer', '/models')).toBe(false);
    expect(canAccessRoute('developer', '/rbac')).toBe(false);
  });

  it('ops 看不到 RBAC/策略', () => {
    expect(canAccessRoute('ops', '/models')).toBe(true);
    expect(canAccessRoute('ops', '/cost')).toBe(true);
    expect(canAccessRoute('ops', '/rbac')).toBe(false);
    expect(canAccessRoute('ops', '/policies')).toBe(false);
  });

  it('未知路由默认放行', () => {
    expect(canAccessRoute('viewer', '/unknown-route')).toBe(true);
  });
});

describe('canPerform', () => {
  it('admin 可删 model', () => {
    expect(canPerform('admin', 'model.delete')).toBe(true);
  });
  it('viewer 不可删 model', () => {
    expect(canPerform('viewer', 'model.delete')).toBe(false);
  });
  it('ops 可建 Key 但不可撤销', () => {
    expect(canPerform('ops', 'apikey.create')).toBe(true);
    expect(canPerform('ops', 'apikey.revoke')).toBe(false);
  });
  it('未声明 action 默认放行', () => {
    expect(canPerform('viewer', 'unknown.action')).toBe(true);
  });
});

describe('usePermission', () => {
  it('返回当前角色是否能执行', () => {
    setRole('admin');
    const { result } = renderHook(() => usePermission('model.delete'));
    expect(result.current).toBe(true);
  });
  it('切换角色后权限变更', () => {
    setRole('viewer');
    const { result } = renderHook(() => usePermission('model.delete'));
    expect(result.current).toBe(false);
  });
});

describe('ROLE_LABEL', () => {
  it('所有角色都有标签', () => {
    expect(ROLE_LABEL.admin).toBeTruthy();
    expect(ROLE_LABEL.ops).toBeTruthy();
    expect(ROLE_LABEL.developer).toBeTruthy();
    expect(ROLE_LABEL.viewer).toBeTruthy();
  });
});
/**
 * policies-live-contract.test.ts — 策略中心 live 契约
 *
 * 后端 AdminPolicyController（GET/POST/PUT/DELETE /v1/admin/rbac/policies）
 * 产出含嵌套 subject{kind,value} / resource{kind,pattern} 的 Policy，
 * 列表按优先级降序。验证前端 policiesApi 直通后端（脱离 SEED）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const store = new Map<string, any>();

vi.mock('../src/lib/request', () => ({
  http: {
    get: vi.fn((path: string) => {
      if (path.startsWith('/admin/rbac/policies')) {
        const list = Array.from(store.values()).sort((a, b) => b.priority - a.priority);
        return Promise.resolve(list);
      }
      return Promise.reject(new Error(`unexpected ${path}`));
    }),
    post: vi.fn((path: string, body: any) => {
      if (path.startsWith('/admin/rbac/policies')) {
        const created = { id: 'p-1', ...body, createdAt: '2026-08-18T01:00:00Z', updatedAt: '2026-08-18T01:00:00Z' };
        store.set(created.id, created);
        return Promise.resolve(created);
      }
      return Promise.reject(new Error(`unexpected POST ${path}`));
    }),
    put: vi.fn((path: string, body: any) => {
      const id = path.split('/').pop()!;
      const existing = store.get(id) ?? { id };
      const next = { ...existing, ...body, id, updatedAt: '2026-08-18T02:00:00Z' };
      store.set(id, next);
      return Promise.resolve(next);
    }),
    delete: vi.fn((path: string) => {
      const id = path.split('/').pop()!;
      store.delete(id);
      return Promise.resolve({ deleted: id });
    }),
  },
  getApiKey: () => '',
  getTenant: () => 'primary',
}));

import { policiesApi } from '../src/lib/api/policies';

describe('policies live 契约（后端 /admin/rbac/policies 对齐）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    store.clear();
  });

  it('CRUD 直通 + 嵌套结构保留 + 优先级降序', async () => {
    // create：嵌套 subject/resource 原样往返
    const created = await policiesApi.create({
      name: '开发者调用白名单',
      priority: 400,
      subject: { kind: 'role', value: 'developer' },
      resource: { kind: 'model', pattern: 'gpt-4o' },
      action: 'invoke',
      effect: 'allow',
      enabled: true,
    });
    expect(created.id).toBe('p-1');
    expect(created.subject).toEqual({ kind: 'role', value: 'developer' });
    expect(created.resource).toEqual({ kind: 'model', pattern: 'gpt-4o' });

    // 再建一条更高优先级 → list 降序
    store.set('p-2', { id: 'p-2', name: 'admin', priority: 1000, subject: { kind: 'role', value: 'admin' }, resource: { kind: '*', pattern: '*' }, action: '*', effect: 'allow', enabled: true });
    const list = await policiesApi.list();
    expect(list).toHaveLength(2);
    expect(list[0].id).toBe('p-2'); // 1000 排前

    // update 部分字段
    const updated = await policiesApi.update('p-1', { enabled: false, priority: 500 });
    expect(updated.enabled).toBe(false);
    expect(updated.subject).toEqual({ kind: 'role', value: 'developer' }); // 未覆盖字段保留

    // delete
    const del = await policiesApi.delete('p-1');
    expect(del.deleted).toBe('p-1');
    expect(await policiesApi.list()).toHaveLength(1);
  });
});

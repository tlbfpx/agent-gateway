/**
 * ratelimit-live-contract.test.ts — 限流监控 live 契约
 *
 * 后端 AdminRateLimitController 产出：
 *   GET /v1/admin/ratelimit/quotas?range=5m  → QuotaRow[]
 *   GET /v1/admin/ratelimit/events?range=24h → RateLimitEvent[]
 *
 * 验证前端 loadQuotas 命中后 live=true 并直用服务端数值，
 * 限流监控页从「◐ 派生」切换为「● 实时」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const liveRows = [
  { id: 'primary', name: 'primary', dim: 'tenant' as const, current: 3.2, limit: 100, blocked: 0 },
  { id: 'alice@primary', name: 'alice@primary', dim: 'user' as const, current: 0.8, limit: 10, blocked: 2, lastBlockedAt: '2026-08-18T01:00:00Z' },
  { id: 'gpt-4o', name: 'gpt-4o', dim: 'agent' as const, current: 5, limit: 50, blocked: 0 },
  { id: 'primary', name: 'primary', dim: 'token-daily' as const, current: 45000, limit: 1_000_000, blocked: 0 },
];
const liveEvents = [
  { id: 'x1', time: '2026-08-18T01:00:00Z', dim: 'user' as const, id2: 'alice@primary', name: 'alice@primary', current: 12, limit: 10, reason: 'qps exceeded' },
];

vi.mock('../src/lib/request', () => ({
  http: {
    get: vi.fn((path: string) => {
      if (path.startsWith('/admin/ratelimit/quotas')) return Promise.resolve(structuredClone(liveRows));
      if (path.startsWith('/admin/ratelimit/events')) return Promise.resolve(structuredClone(liveEvents));
      return Promise.reject(new Error(`unexpected ${path}`));
    }),
  },
  getApiKey: () => '',
  getTenant: () => 'primary',
}));

import { loadQuotas } from '../src/lib/api/ratelimit';

describe('ratelimit live 契约（后端 /admin/ratelimit 对齐）', () => {
  beforeEach(() => vi.clearAllMocks());

  it('两个 live 接口命中 → live=true，直用服务端数值', async () => {
    const r = await loadQuotas();
    expect(r.live).toBe(true);
    expect(r.rows).toHaveLength(4);
    const alice = r.rows.find((x) => x.dim === 'user')!;
    expect(alice.id).toBe('alice@primary');
    expect(alice.blocked).toBe(2);
    expect(alice.lastBlockedAt).toBeTruthy();
    const td = r.rows.find((x) => x.dim === 'token-daily')!;
    expect(td.current).toBe(45000);
    expect(r.events).toHaveLength(1);
    expect(r.events[0].reason).toBe('qps exceeded');
  });
});

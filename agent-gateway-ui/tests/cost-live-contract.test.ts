/**
 * cost-live-contract.test.ts — 成本中心 live 契约
 *
 * 后端 GET /v1/admin/metrics/cost?range=xx 产出 CostReport：
 *   { total, byTenant, byKey, byModel, byDay, live: true, range }
 * 每行：{ dim, id, name, calls, tokens, avgLatencyMs, errors, costCny }
 *
 * 验证前端 loadCostReport 命中后 live=true 并直用服务端数值，
 * 成本中心页将从「◐ 派生」切换为「● 实时」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const liveCost = {
  total: { calls: 42, tokens: 63_000, errors: 3, costCny: 12.34, avgLatencyMs: 0 },
  byTenant: [
    { dim: 'tenant', id: 'primary', name: 'primary', calls: 30, tokens: 45_000, avgLatencyMs: 0, errors: 2, costCny: 9.1 },
    { dim: 'tenant', id: 'tenant-b', name: 'tenant-b', calls: 12, tokens: 18_000, avgLatencyMs: 0, errors: 1, costCny: 3.24 },
  ],
  byKey: [{ dim: 'key', id: 'alice@primary', name: 'alice@primary', calls: 42, tokens: 63_000, avgLatencyMs: 0, errors: 3, costCny: 12.34 }],
  byModel: [
    { dim: 'model', id: 'gpt-4o', name: 'gpt-4o', calls: 40, tokens: 60_000, avgLatencyMs: 0, errors: 3, costCny: 11.88 },
    { dim: 'model', id: 'claude-3.7', name: 'claude-3.7', calls: 2, tokens: 3_000, avgLatencyMs: 0, errors: 0, costCny: 0.46 },
  ],
  byDay: [{ dim: 'day', id: '2026-08-18', name: '2026-08-18', calls: 42, tokens: 63_000, avgLatencyMs: 0, errors: 3, costCny: 12.34 }],
  live: true,
  range: '7d',
};

vi.mock('../src/lib/request', () => ({
  http: {
    get: vi.fn((path: string) => {
      if (path.startsWith('/admin/metrics/cost')) return Promise.resolve(structuredClone(liveCost));
      return Promise.reject(new Error(`unexpected ${path}`));
    }),
  },
  getApiKey: () => '',
  getTenant: () => 'primary',
}));
vi.mock('../src/lib/api/audit', () => ({ listAuditLogs: vi.fn().mockResolvedValue([]) }));

import { loadCostReport } from '../src/lib/api/usage';

describe('cost live 契约（后端 /admin/metrics/cost 对齐）', () => {
  beforeEach(() => vi.clearAllMocks());

  it('命中后 live=true，直用服务端四维度数值', async () => {
    const r = await loadCostReport('7d');
    expect(r.live).toBe(true);
    expect(r.range).toBe('7d');
    expect(r.total.calls).toBe(42);
    expect(r.total.costCny).toBeCloseTo(12.34);
    expect(r.byTenant).toHaveLength(2);
    expect(r.byTenant[0].id).toBe('primary');
    expect(r.byModel[0].id).toBe('gpt-4o');
    expect(r.byModel[0].costCny).toBeCloseTo(11.88);
    expect(r.byDay[0].id).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

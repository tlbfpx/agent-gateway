/**
 * metrics-live-contract.test.ts — 前后端契约验证
 *
 * 后端 AdminMetricsController（gateway-interfaces）产出：
 *   GET /v1/admin/metrics/overview     → { requests24h, errorRate, p95LatencyMs }
 *   GET /v1/admin/metrics/usage        → [{ t, n, err }]
 *   GET /v1/admin/metrics/top?by=model → [{ id, name, n, err }]
 *
 * 本测试 mock 同形状响应，验证前端 loadDashboardReport 判定
 * live=true 并使用服务端值（Dashboard 切换为「● 实时」标签）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const liveOverview = { requests24h: 9999, errorRate: 0.0123, p95LatencyMs: 210 };
const liveUsage = Array.from({ length: 24 }, (_, i) => ({ t: `${String(i).padStart(2, '0')}:00`, n: i * 7, err: i % 5 === 0 ? 1 : 0 }));
const liveTop = [
  { id: 'gpt-4o', name: 'gpt-4o', n: 120, err: 2 },
  { id: 'claude', name: 'claude', n: 80, err: 0 },
];

vi.mock('../src/lib/request', () => ({
  http: {
    get: vi.fn((path: string) => {
      if (path.startsWith('/admin/metrics/overview')) return Promise.resolve({ ...liveOverview });
      if (path.startsWith('/admin/metrics/usage')) return Promise.resolve(liveUsage);
      if (path.startsWith('/admin/metrics/top')) return Promise.resolve(liveTop);
      return Promise.reject(new Error(`unexpected ${path}`));
    }),
  },
  getApiKey: () => '',
  getTenant: () => 'primary',
}));
vi.mock('../src/lib/api/models', () => ({ listModels: vi.fn().mockResolvedValue([]) }));
vi.mock('../src/lib/api/keys', () => ({ listApiKeys: vi.fn().mockResolvedValue([]) }));
vi.mock('../src/lib/api/audit', () => ({ listAuditLogs: vi.fn().mockResolvedValue([]) }));
vi.mock('../src/lib/api/health', () => ({ getHealth: vi.fn().mockResolvedValue({ status: 'UP', components: {} }) }));

import { loadDashboardReport } from '../src/lib/api/usage';

describe('metrics live 契约（后端 AdminMetricsController 对齐）', () => {
  beforeEach(() => vi.clearAllMocks());

  it('三个 live 接口命中 → live=true，使用服务端数值', async () => {
    const r = await loadDashboardReport();
    expect(r.live).toBe(true);
    expect(r.overview.requests24h).toBe(9999);
    expect(r.overview.errorRate).toBeCloseTo(0.0123);
    expect(r.usage24h).toHaveLength(24);
    expect(r.usage24h[3]).toEqual({ t: '03:00', n: 21, err: 0 });
    expect(r.topModels[0].id).toBe('gpt-4o');
    expect(r.topModels[0].n).toBe(120);
  });
});

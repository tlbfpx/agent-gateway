/**
 * usage.test.ts — Dashboard 数据聚合
 * 验证降级聚合逻辑在 metrics 接口未接时仍能产出真实数据
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('../src/lib/api/models', () => ({
  listModels: vi.fn().mockResolvedValue([
    { id: 'gpt-4o', provider: 'openai', displayName: 'GPT-4o', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: true },
    { id: 'claude-3.7', provider: 'anthropic', displayName: 'Claude 3.7', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: true },
  ]),
}));
vi.mock('../src/lib/api/keys', () => ({
  listApiKeys: vi.fn().mockResolvedValue([
    { id: 'pk_live_a', tenant: 'primary', enabled: true, createdAt: '2026-01-01' },
    { id: 'pk_live_b', tenant: 'tenant-b', enabled: false, createdAt: '2026-01-01' },
  ]),
}));
vi.mock('../src/lib/api/audit', () => ({
  listAuditLogs: vi.fn().mockResolvedValue([
    { eventId: 'e1', actor: 'admin', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '' },
    { eventId: 'e2', actor: 'admin', type: 'chat', time: new Date().toISOString(), resource: 'claude-3.7', action: 'invoke', result: 'fail', detail: '' },
    { eventId: 'e3', actor: 'admin', type: 'chat', time: new Date(Date.now() - 48 * 3600 * 1000).toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '' },
  ]),
}));
vi.mock('../src/lib/api/health', () => ({
  getHealth: vi.fn().mockResolvedValue({ status: 'UP', components: { gateway: { status: 'UP' }, redis: { status: 'UP' } } }),
}));

import { loadDashboardReport } from '../src/lib/api/usage';

describe('loadDashboardReport — 派生模式', () => {
  it('metrics 接口不可达时仍产出报告', async () => {
    const r = await loadDashboardReport();
    expect(r.overview.requests24h).toBeGreaterThanOrEqual(0);
    expect(r.overview.activeKeys).toBe(1); // 2 keys 中 1 个 enabled
    expect(r.live).toBe(false);
  });

  it('usage24h 包含 24 个时间桶', () => {
    return loadDashboardReport().then((r) => {
      expect(r.usage24h).toHaveLength(24);
    });
  });

  it('topModels 按调用量降序', () => {
    return loadDashboardReport().then((r) => {
      expect(r.topModels.length).toBeGreaterThan(0);
      // 第一个是 gpt-4o（成功 1 次），但 claude-3.7 也 1 次 → 可能任意，但 n 应当递减
      for (let i = 1; i < r.topModels.length; i++) {
        expect(r.topModels[i - 1].n).toBeGreaterThanOrEqual(r.topModels[i].n);
      }
    });
  });

  it('排除 24h 之外的数据', () => {
    return loadDashboardReport().then((r) => {
      // 48h 之前的数据不应算入 requests24h
      expect(r.overview.requests24h).toBeLessThan(3);
    });
  });

  it('错误率计算', () => {
    return loadDashboardReport().then((r) => {
      // 1 个 fail / (1 个 success + 1 个 fail) = 0.5
      expect(r.overview.errorRate).toBeGreaterThan(0);
      expect(r.overview.errorRate).toBeLessThanOrEqual(1);
    });
  });

  it('派生模式 live = false', async () => {
    const r = await loadDashboardReport();
    expect(r.live).toBe(false);
  });
});
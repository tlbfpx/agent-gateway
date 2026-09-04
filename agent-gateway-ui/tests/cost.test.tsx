/**
 * cost.test.ts — 成本中心聚合
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../src/lib/api/models', () => ({
  listModels: vi.fn().mockResolvedValue([]),
}));
vi.mock('../src/lib/api/keys', () => ({
  listApiKeys: vi.fn().mockResolvedValue([]),
}));
vi.mock('../src/lib/api/audit', () => ({
  listAuditLogs: vi.fn().mockResolvedValue([
    { eventId: 'e1', actor: 'admin@primary', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary' },
    { eventId: 'e2', actor: 'admin@tenant-b', type: 'chat', time: new Date().toISOString(), resource: 'claude-3.7', action: 'invoke', result: 'fail', detail: '', tenant: 'tenant-b' },
    { eventId: 'e3', actor: 'admin@primary', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary' },
    { eventId: 'e4', actor: 'admin@tenant-b', type: 'chat', time: new Date(Date.now() - 60 * 86400 * 1000).toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'tenant-b' },
  ]),
}));
vi.mock('../src/lib/api/health', () => ({
  getHealth: vi.fn().mockResolvedValue({ status: 'UP', components: {} }),
}));

import { loadCostReport } from '../src/lib/api/usage';

describe('loadCostReport', () => {
  it('派生模式聚合 4 个维度', async () => {
    const r = await loadCostReport('7d');
    expect(r.range).toBe('7d');
    expect(r.live).toBe(false);
    expect(r.byTenant.length).toBeGreaterThan(0);
    expect(r.byModel.length).toBeGreaterThan(0);
    expect(r.byDay.length).toBeGreaterThan(0);
  });

  it('成本按调用量正相关', async () => {
    const r = await loadCostReport('7d');
    expect(r.total.costCny).toBeGreaterThan(0);
    expect(r.total.calls).toBeGreaterThan(0);
  });

  it('24h 排除 7 天前数据', async () => {
    const r = await loadCostReport('24h');
    // 60 天前的 audit 不应计入
    expect(r.total.calls).toBe(3);
  });

  it('byDay 按日期分桶', async () => {
    const r = await loadCostReport('30d');
    for (const d of r.byDay) {
      // id 应形如 YYYY-MM-DD
      expect(d.id).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    }
  });

  it('失败次数累加正确', async () => {
    const r = await loadCostReport('30d');
    // tenant-b 1 个 fail
    const tb = r.byTenant.find((x) => x.id === 'tenant-b');
    expect(tb).toBeDefined();
    expect(tb!.errors).toBe(1);
  });

  it('按成本降序排列', async () => {
    const r = await loadCostReport('7d');
    for (let i = 1; i < r.byTenant.length; i++) {
      expect(r.byTenant[i - 1].costCny).toBeGreaterThanOrEqual(r.byTenant[i].costCny);
    }
    for (let i = 1; i < r.byModel.length; i++) {
      expect(r.byModel[i - 1].costCny).toBeGreaterThanOrEqual(r.byModel[i].costCny);
    }
  });

  it('不同 range 返回不同数据', async () => {
    const r24h = await loadCostReport('24h');
    const r7d = await loadCostReport('7d');
    // 24h 数据 ≤ 7d 数据（窗口更小）
    expect(r24h.total.calls).toBeLessThanOrEqual(r7d.total.calls);
  });
});

/* ─────────── Round4 追加：URL 状态同步 ─────────── */
import { screen, waitFor } from '@testing-library/react';
import { CostCenter } from '../src/pages/CostCenter';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

const liveCost24h = {
  total: { calls: 6, tokens: 9_000, errors: 1, costCny: 2.2, avgLatencyMs: 320 },
  byTenant: [{ dim: 'tenant', id: 'primary', name: 'primary', calls: 6, tokens: 9_000, avgLatencyMs: 320, errors: 1, costCny: 2.2 }],
  byKey: [{ dim: 'key', id: 'alice@primary', name: 'alice@primary', calls: 6, tokens: 9_000, avgLatencyMs: 320, errors: 1, costCny: 2.2 }],
  byModel: [{ dim: 'model', id: 'gpt-4o', name: 'gpt-4o', calls: 6, tokens: 9_000, avgLatencyMs: 320, errors: 1, costCny: 2.2 }],
  byDay: [{ dim: 'day', id: '2026-08-30', name: '2026-08-30', calls: 6, tokens: 9_000, avgLatencyMs: 320, errors: 1, costCny: 2.2 }],
  live: true,
  range: '7d',
};

describe('CostCenter — URL 状态同步（Round4）', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
    mock.on('GET', '/admin/metrics/cost', () =>
      new Response(JSON.stringify(liveCost24h), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
  });
  afterEach(() => mock.uninstall());

  it('?range=7d&dim=model 初始进入渲染按模型 Tab', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d&dim=model' });
    await screen.findByText('成本中心');
    await waitFor(() => {
      const active = document.querySelector('.ant-tabs-tab-active');
      expect(active?.textContent).toContain('按模型');
    });
  });
});
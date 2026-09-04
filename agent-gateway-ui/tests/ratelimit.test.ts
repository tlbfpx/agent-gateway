/**
 * ratelimit.test.ts — 限流派生数据
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('../src/lib/api/audit', () => ({
  listAuditLogs: vi.fn().mockResolvedValue([
    { eventId: 'e1', actor: 'alice@primary', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary' },
    { eventId: 'e2', actor: 'bob@tenant-b', type: 'chat', time: new Date().toISOString(), resource: 'claude-3.7', action: 'invoke', result: 'deny', detail: '', tenant: 'tenant-b' },
    { eventId: 'e3', actor: 'alice@primary', type: 'chat', time: new Date(Date.now() - 60 * 86400 * 1000).toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary' },
    { eventId: 'e4', actor: 'bob@tenant-b', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'tenant-b' },
  ]),
}));

import { loadQuotas, ALL_DIMS_LIST } from '../src/lib/api/ratelimit';

describe('loadQuotas — 派生模式', () => {
  it('至少包含 token-daily 维度（5m 无活动也有）', async () => {
    const r = await loadQuotas();
    const seen = new Set(r.rows.map((row) => row.dim));
    expect(seen.has('token-daily')).toBe(true);
  });

  it('常见维度（tenant/user/agent）有数据', async () => {
    const r = await loadQuotas();
    const seen = new Set(r.rows.map((row) => row.dim));
    expect(seen.has('tenant')).toBe(true);
    expect(seen.has('user')).toBe(true);
    expect(seen.has('agent')).toBe(true);
  });

  it('live=false 时降级', async () => {
    const r = await loadQuotas();
    expect(r.live).toBe(false);
  });

  it('只统计 5m 窗口的当前用量', async () => {
    const r = await loadQuotas();
    // 60 天前的 1 个 success 不计入 user/agent
    const userRows = r.rows.filter((row) => row.dim === 'user');
    // alice@primary + bob@tenant-b
    expect(userRows.length).toBe(2);
  });

  it('token-daily 单对象', async () => {
    const r = await loadQuotas();
    const td = r.rows.filter((row) => row.dim === 'token-daily');
    expect(td.length).toBe(1);
    expect(td[0].id).toBe('primary');
  });

  it('deny 事件产生限流事件', async () => {
    const r = await loadQuotas();
    // bob 的 deny 应进入 events
    expect(r.events.length).toBeGreaterThan(0);
    expect(r.events.some((e) => e.id2 === 'bob@tenant-b')).toBe(true);
  });

  it('每行有 limit 字段', async () => {
    const r = await loadQuotas();
    for (const row of r.rows) {
      expect(row.limit).toBeGreaterThan(0);
    }
  });
});
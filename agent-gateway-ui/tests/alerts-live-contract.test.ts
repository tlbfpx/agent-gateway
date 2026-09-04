/**
 * alerts-live-contract.test.ts — 告警中心 live 契约(spec 2026-08-19 §5.4/§5.5)
 *
 * 后端 AdminAlertController 产出:
 *   GET/POST/PUT/DELETE /v1/admin/alerts/rules
 *   GET  /v1/admin/alerts?state=&severity=
 *   POST /v1/admin/alerts/:id/ack | /silence
 *   GET  /v1/admin/alerts/events?range=24h(旧视图兼容)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const ruleStore = new Map<string, any>();
let records: any[] = [];

vi.mock('../src/lib/request', () => ({
  http: {
    get: vi.fn((path: string) => {
      if (path.startsWith('/admin/alerts/rules')) {
        return Promise.resolve(Array.from(ruleStore.values()));
      }
      if (path.startsWith('/admin/alerts?') || path === '/admin/alerts') {
        return Promise.resolve(records);
      }
      if (path.startsWith('/admin/alerts/events')) {
        return Promise.resolve([
          { id: 'x-1', ruleId: 'rate-limit', ruleName: '限流触发', severity: 'warning', metric: 'rate_limit_hit', value: 12, threshold: 10, time: '2026-08-18T01:00:00Z', message: 'qps 12 > 10', acknowledged: false },
        ]);
      }
      return Promise.reject(new Error(`unexpected ${path}`));
    }),
    post: vi.fn((path: string, body?: any) => {
      if (path.startsWith('/admin/alerts/rules')) {
        const created = { id: 'ar-1', createdAt: '2026-08-18T01:00:00Z', ...body };
        ruleStore.set(created.id, created);
        return Promise.resolve(created);
      }
      if (path.endsWith('/ack') || path.endsWith('/silence')) {
        const id = path.split('/')[3];
        const target = records.find((r) => r.id === id);
        if (path.endsWith('/ack')) {
          return Promise.resolve({ ...target, claimedBy: body?.claimedBy ?? 'admin' });
        }
        return Promise.resolve({ ...target, note: 'silenced' });
      }
      return Promise.reject(new Error(`unexpected POST ${path}`));
    }),
    put: vi.fn((path: string, body: any) => {
      const id = path.split('/').pop()!;
      const existing = ruleStore.get(id) ?? { id };
      const next = { ...existing, ...body, id, updatedAt: '2026-08-18T02:00:00Z' };
      ruleStore.set(id, next);
      return Promise.resolve(next);
    }),
    delete: vi.fn((path: string) => {
      const id = path.split('/').pop()!;
      ruleStore.delete(id);
      return Promise.resolve({ deleted: id });
    }),
  },
  getApiKey: () => '',
  getTenant: () => 'primary',
}));

import { alertsApi } from '../src/lib/api/alerts';

describe('alerts live 契约(后端 /admin/alerts 对齐)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    ruleStore.clear();
    records = [
      {
        id: 'al-1', ruleId: 'r-1', severity: 'critical', state: 'firing',
        dedupKey: 'r-1:chat.errors', labels: { rule: '错误过多' },
        firstFiredAt: '2026-08-18T00:00:00Z', recentlyTriggeredAt: '2026-08-18T01:00:00Z',
        triggerCount: 3, observedValue: 12, threshold: 5,
        claimedBy: null, note: null, resolvedAt: null,
      },
    ];
  });

  it('规则 CRUD 全链路直通后端', async () => {
    const created = await alertsApi.rules.create({
      name: '网关错误超10', metricName: 'gateway.errors', operator: 'GT',
      threshold: 10, windowSeconds: 300, severity: 'critical', enabled: true,
    });
    expect(created.id).toBe('ar-1');
    expect(created.createdAt).toBeTruthy();

    const rules = await alertsApi.rules.list();
    expect(rules).toHaveLength(1);
    expect(rules[0].name).toBe('网关错误超10');

    const updated = await alertsApi.rules.update('ar-1', { enabled: false });
    expect(updated.enabled).toBe(false);
    expect(updated.name).toBe('网关错误超10'); // 未覆盖字段保留

    const del = await alertsApi.rules.remove('ar-1');
    expect(del.deleted).toBe('ar-1');
    expect(await alertsApi.rules.list()).toHaveLength(0);
  });

  it('告警流查询 + 认领/静默', async () => {
    const flow = await alertsApi.records();
    expect(flow).toHaveLength(1);
    expect(flow[0].state).toBe('firing');
    expect(flow[0].triggerCount).toBe(3);

    const acked = await alertsApi.ack('al-1', 'ops-bob', '处理中');
    expect(acked.claimedBy).toBe('ops-bob');

    const silenced = await alertsApi.silence('al-1', '夜间静默');
    expect(silenced.note).toContain('silenced');
  });

  it('旧事件视图兼容保留', async () => {
    const events = await alertsApi.events();
    expect(events).toHaveLength(1);
    expect(events[0].metric).toBe('rate_limit_hit');
  });
});

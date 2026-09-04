/**
 * sidebar-counts.test.ts — Sidebar 状态徽章 helper
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('../src/lib/api/models', () => ({
  listModels: vi.fn().mockResolvedValue([
    { id: 'gpt-4o', provider: 'openai', displayName: 'GPT-4o', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: true },
    { id: 'claude-3.7', provider: 'anthropic', displayName: 'Claude 3.7', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: false },
  ]),
}));
vi.mock('../src/lib/api/keys', () => ({
  listApiKeys: vi.fn().mockResolvedValue([
    { id: 'k1', tenant: 'primary', enabled: true, createdAt: '' },
    { id: 'k2', tenant: 'primary', enabled: false, createdAt: '' },
  ]),
}));
vi.mock('../src/lib/api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([
    { name: 'a', description: '', skills: [], available: true },
  ]),
}));
vi.mock('../src/lib/api/webhooks', () => ({
  listWebhooks: vi.fn().mockResolvedValue([
    { url: 'x', events: ['chat.completed'], active: true },
  ]),
}));
vi.mock('../src/lib/api/alerts', () => ({
  alertsApi: {
    events: vi.fn().mockResolvedValue([
      { id: 'e1', ruleId: 'r', ruleName: 'r', severity: 'critical', metric: 'error_rate', value: 0.1, threshold: 0.05, time: '', message: '', acknowledged: false },
    ]),
  },
}));

import { useSidebarCounts, getCount, isCritical } from '../src/hooks/useSidebarCounts';
import { renderHook, act, waitFor } from '@testing-library/react';

describe('useSidebarCounts', () => {
  it('拉取所有源数据并聚合', async () => {
    const { result } = renderHook(() => useSidebarCounts());
    await waitFor(() => {
      expect(result.current.counts.models.total).toBeGreaterThan(0);
    });
    expect(result.current.counts.models.total).toBe(2);
    expect(result.current.counts.models.online).toBe(1);
    expect(result.current.counts.apiKeys.active).toBe(1);
    expect(result.current.counts.agents.total).toBe(1);
    expect(result.current.counts.alerts.unread).toBe(1);
    expect(result.current.counts.alerts.critical).toBe(1);
  });

  it('refresh() 可手动触发', async () => {
    const { result } = renderHook(() => useSidebarCounts());
    await waitFor(() => {
      expect(result.current.counts.models.total).toBe(2);
    });
    act(() => result.current.refresh());
    await waitFor(() => {
      expect(result.current.counts.models.total).toBe(2);
    });
  });
});

describe('getCount', () => {
  it('models → "online/total"', () => {
    expect(
      getCount({ models: { online: 3, total: 5 }, apiKeys: { active: 0 }, agents: { online: 0, total: 0 }, webhooks: { active: 0 }, alerts: { unread: 0, critical: 0 }, ratelimit: { blocked: 0 }, chat: { sessions: 0 } }, 'models'),
    ).toBe('3/5');
  });
  it('models 仅 total 时只显示总数', () => {
    expect(
      getCount({ models: { online: 0, total: 5 }, apiKeys: { active: 0 }, agents: { online: 0, total: 0 }, webhooks: { active: 0 }, alerts: { unread: 0, critical: 0 }, ratelimit: { blocked: 0 }, chat: { sessions: 0 } }, 'models'),
    ).toBe('5');
  });
  it('apiKeys active 为 0 返回 null（隐藏徽章）', () => {
    expect(
      getCount({ models: { online: 0, total: 0 }, apiKeys: { active: 0 }, agents: { online: 0, total: 0 }, webhooks: { active: 0 }, alerts: { unread: 0, critical: 0 }, ratelimit: { blocked: 0 }, chat: { sessions: 0 } }, 'apiKeys'),
    ).toBe(null);
  });
  it('alerts unread > 0 显示', () => {
    expect(
      getCount({ models: { online: 0, total: 0 }, apiKeys: { active: 0 }, agents: { online: 0, total: 0 }, webhooks: { active: 0 }, alerts: { unread: 5, critical: 1 }, ratelimit: { blocked: 0 }, chat: { sessions: 0 } }, 'alerts'),
    ).toBe('5');
  });
});

describe('isCritical', () => {
  const c = { models: { online: 0, total: 0 }, apiKeys: { active: 0 }, agents: { online: 0, total: 0 }, webhooks: { active: 0 }, alerts: { unread: 5, critical: 1 }, ratelimit: { blocked: 3 }, chat: { sessions: 0 } };
  it('alerts + critical → true', () => {
    expect(isCritical('alerts', c)).toBe(true);
  });
  it('ratelimit blocked > 0 → true', () => {
    expect(isCritical('ratelimit', c)).toBe(true);
  });
  it('无 critical 时 → false', () => {
    expect(isCritical('alerts', { ...c, alerts: { unread: 0, critical: 0 } })).toBe(false);
  });
});
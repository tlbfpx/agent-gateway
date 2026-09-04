/**
 * useSidebarCounts — Sidebar 各菜单项实时计数
 *
 * 让侧栏从「导航」升级为「实时状态板」：
 *   /models       显示「在线模型 N / 总 N」
 *   /api-keys     显示「活跃 N」
 *   /agents       显示「启用 N / 总 N」
 *   /webhooks     显示「订阅 N」
 *   /alerts       显示「未读 N」（critical 红色）
 *   /ratelimit    显示「429 触发 N」
 *   /chat         显示「会话 N」
 *
 * 性能：30s 静默刷新 + 失败容错（接口不可达时不阻塞 UI）
 */
import { useEffect, useState, useCallback } from 'react';
import { listModels } from '../lib/api/models';
import { listApiKeys } from '../lib/api/keys';
import { listAgents as listAgentsPublic } from '../lib/api/agents';
import { listWebhooks } from '../lib/api/webhooks';
import { alertsApi } from '../lib/api/alerts';

export interface SidebarCounts {
  models: { online: number; total: number };
  apiKeys: { active: number };
  agents: { online: number; total: number };
  webhooks: { active: number };
  alerts: { unread: number; critical: number };
  ratelimit: { blocked: number };
  chat: { sessions: number };
  cost?: { todayCny: number };
}

const EMPTY: SidebarCounts = {
  models: { online: 0, total: 0 },
  apiKeys: { active: 0 },
  agents: { online: 0, total: 0 },
  webhooks: { active: 0 },
  alerts: { unread: 0, critical: 0 },
  ratelimit: { blocked: 0 },
  chat: { sessions: 0 },
};

let _cache: { value: SidebarCounts; ts: number } | null = null;
const TTL = 30_000;

const _subs = new Set<(c: SidebarCounts) => void>();

function publish(c: SidebarCounts) {
  _cache = { value: c, ts: Date.now() };
  _subs.forEach((fn) => fn(c));
}

async function fetchOnce(): Promise<SidebarCounts> {
  const out: SidebarCounts = { ...EMPTY };
  await Promise.allSettled([
    listModels().then((ms) => {
      out.models.total = ms.length;
      out.models.online = ms.filter((m) => m.enabled).length;
    }),
    listApiKeys().then((ks) => {
      out.apiKeys.active = ks.filter((k) => k.enabled).length;
    }),
    listAgentsPublic().then((as) => {
      out.agents.total = as.length;
      out.agents.online = as.filter((a) => a.available).length;
    }),
    listWebhooks().then((ws) => {
      out.webhooks.active = ws.filter((w) => w.active !== false).length;
    }),
    alertsApi.events().then((evs) => {
      out.alerts.unread = evs.filter((e) => !e.acknowledged).length;
      out.alerts.critical = evs.filter((e) => !e.acknowledged && e.severity === 'critical').length;
    }),
  ]);
  return out;
}

export function useSidebarCounts() {
  const [counts, setCounts] = useState<SidebarCounts>(_cache?.value ?? EMPTY);

  const refresh = useCallback(async () => {
    try {
      const c = await fetchOnce();
      publish(c);
    } catch {
      /* 容错：不抛错就 */
    }
  }, []);

  useEffect(() => {
    const fn = (c: SidebarCounts) => setCounts(c);
    _subs.add(fn);
    if (!_cache || Date.now() - _cache.ts > TTL) {
      refresh();
    } else {
      setCounts(_cache.value);
    }
    const t = setInterval(refresh, TTL);
    return () => {
      _subs.delete(fn);
      clearInterval(t);
    };
  }, [refresh]);

  return { counts, refresh };
}

/** 给 Sidebar 直接取数字的 helper（带 fallback） */
export function getCount(counts: SidebarCounts, key: keyof SidebarCounts): number | string | null {
  switch (key) {
    case 'models':
      return counts.models.online > 0
        ? `${counts.models.online}/${counts.models.total}`
        : `${counts.models.total}`;
    case 'apiKeys':
      return counts.apiKeys.active > 0 ? `${counts.apiKeys.active}` : null;
    case 'agents':
      return counts.agents.total > 0
        ? `${counts.agents.online}/${counts.agents.total}`
        : null;
    case 'webhooks':
      return counts.webhooks.active > 0 ? `${counts.webhooks.active}` : null;
    case 'alerts':
      return counts.alerts.unread > 0 ? `${counts.alerts.unread}` : null;
    case 'ratelimit':
      return counts.ratelimit.blocked > 0 ? `${counts.ratelimit.blocked}` : null;
    case 'chat':
      return counts.chat.sessions > 0 ? `${counts.chat.sessions}` : null;
    case 'cost':
      return null;
  }
}

/** 给 Sidebar 决定是否显示为 critical 红色徽章 */
export function isCritical(key: keyof SidebarCounts, counts: SidebarCounts): boolean {
  if (key === 'alerts') return counts.alerts.critical > 0;
  if (key === 'ratelimit') return counts.ratelimit.blocked > 0;
  return false;
}
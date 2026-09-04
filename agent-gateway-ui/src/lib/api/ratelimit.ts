/**
 * ratelimit.ts — 限流实时监控
 *
 * 后端接口（待 bootstrap 接线）：
 *   GET /v1/admin/ratelimit/quotas?range=5m     → QuotaRow[]
 *   GET /v1/admin/ratelimit/events?range=24h    → RateLimitEvent[]
 *
 * 5 维度（README §）：
 *  1. 租户 QPS
 *  2. 用户 QPS
 *  3. API Key QPS
 *  4. Agent 并发
 *  5. Token 日预算
 *
 * 降级策略：接口不可达时从 audit 派生近似数据。
 */
import { http } from '../request';

export type Dim = 'tenant' | 'user' | 'key' | 'agent' | 'token-daily';

export interface QuotaRow {
  /** 维度对象 ID */
  id: string;
  /** 显示名称 */
  name: string;
  /** 维度 */
  dim: Dim;
  /** 当前值（5m 窗口 QPS / 24h token 数 / 当前并发） */
  current: number;
  /** 限额 */
  limit: number;
  /** 触发 429 次数（窗口内） */
  blocked: number;
  /** 最近一次触发时间 */
  lastBlockedAt?: string;
}

export interface RateLimitEvent {
  id: string;
  time: string;
  dim: Dim;
  id2: string;
  name: string;
  current: number;
  limit: number;
  reason: string;
}

export interface QuotasReport {
  rows: QuotaRow[];
  events: RateLimitEvent[];
  live: boolean;
}

const ALL_DIMS: Dim[] = ['tenant', 'user', 'key', 'agent', 'token-daily'];

/** 演示用限额（真实场景从网关配置拉取） */
const LIMITS: Record<Dim, number> = {
  tenant: 100, // 100 QPS per tenant
  user: 10, // 10 QPS per user
  key: 30, // 30 QPS per key
  agent: 50, // 50 concurrent per agent
  'token-daily': 1_000_000, // 1M tokens/day
};

async function fetchLiveQuotas(): Promise<QuotaRow[] | null> {
  try {
    return await http.get<QuotaRow[]>('/admin/ratelimit/quotas?range=5m');
  } catch {
    return null;
  }
}

async function fetchLiveEvents(): Promise<RateLimitEvent[] | null> {
  try {
    return await http.get<RateLimitEvent[]>('/admin/ratelimit/events?range=24h');
  } catch {
    return null;
  }
}

/** 派生模式：从 audit 估算 5 维用量 */
async function deriveFromAudit(): Promise<{ rows: QuotaRow[]; events: RateLimitEvent[] }> {
  const { listAuditLogs } = await import('./audit');
  const audit = await listAuditLogs({ tenant: 'primary', limit: 500 }).catch(() => []);
  const now = Date.now();
  const recent5m = audit.filter((a) => now - new Date(a.time).getTime() < 5 * 60_000);
  const recent24h = audit.filter((a) => now - new Date(a.time).getTime() < 24 * 3600_000);

  const groups: Record<Dim, Map<string, { current: number; blocked: number }>> = {
    tenant: new Map(),
    user: new Map(),
    key: new Map(),
    agent: new Map(),
    'token-daily': new Map(),
  };

  // 5m 窗口：按维度计数
  for (const a of recent5m) {
    const tenant = (a as any).tenant ?? 'primary';
    const actor = a.actor ?? 'unknown';
    const agent = a.resource?.split('/').pop() ?? 'unknown';

    for (const [dim, id] of [
      ['tenant', tenant] as [Dim, string],
      ['user', actor] as [Dim, string],
      ['agent', agent] as [Dim, string],
    ]) {
      const cur = groups[dim].get(id) ?? { current: 0, blocked: 0 };
      cur.current++;
      groups[dim].set(id, cur);
    }
  }

  // 24h 窗口：deny 事件 + token 估算
  const events: RateLimitEvent[] = [];
  for (const a of recent24h) {
    if (a.result === 'deny') {
      events.push({
        id: a.eventId,
        time: a.time,
        dim: 'user',
        id2: a.actor ?? 'unknown',
        name: a.actor ?? 'unknown',
        current: 0,
        limit: LIMITS.user,
        reason: a.action ?? 'denied',
      });
      const cur = groups.user.get(a.actor ?? 'unknown') ?? { current: 0, blocked: 0 };
      cur.blocked++;
      groups.user.set(a.actor ?? 'unknown', cur);
    }
  }

  // token-daily：单一对象 primary，估算 token 消耗
  const tokens24h = recent24h.length * 1500;
  groups['token-daily'].set('primary', { current: tokens24h, blocked: 0 });

  // 展开成 QuotaRow[]
  const rows: QuotaRow[] = [];
  for (const dim of ALL_DIMS) {
    for (const [id, v] of groups[dim]) {
      // 5m 窗口请求数 → QPS
      const value =
        dim === 'token-daily'
          ? v.current
          : dim === 'agent'
            ? v.current // 视为并发
            : Math.round((v.current / 300) * 100) / 100; // 5m 平均 QPS
      rows.push({
        id,
        name: id,
        dim,
        current: value,
        limit: LIMITS[dim],
        blocked: v.blocked,
        lastBlockedAt: v.blocked > 0 ? new Date().toISOString() : undefined,
      });
    }
  }
  return { rows, events };
}

export async function loadQuotas(): Promise<QuotasReport> {
  const [liveQuotas, liveEvents, derived] = await Promise.allSettled([
    fetchLiveQuotas(),
    fetchLiveEvents(),
    deriveFromAudit(),
  ]);

  if (
    liveQuotas.status === 'fulfilled' &&
    liveQuotas.value != null &&
    liveEvents.status === 'fulfilled' &&
    liveEvents.value != null
  ) {
    return {
      rows: liveQuotas.value,
      events: liveEvents.value,
      live: true,
    };
  }

  const d = derived.status === 'fulfilled' ? derived.value : { rows: [], events: [] };
  return { rows: d.rows, events: d.events, live: false };
}

export const ALL_DIMS_LIST = ALL_DIMS;
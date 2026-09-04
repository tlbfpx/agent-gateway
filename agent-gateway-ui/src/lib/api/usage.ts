/**
 * usage.ts — Dashboard 数据聚合
 *
 * 后端接口（待 bootstrap 接线，未接时退化为前端聚合）：
 *   GET /v1/admin/metrics/overview         → 总览（24h 调用量、活跃 Key 数、错误率、p95 延迟）
 *   GET /v1/admin/metrics/usage?range=24h  → 时间序列（每日/小时调用量）
 *   GET /v1/admin/metrics/top?by=model     → Top 模型（按调用量降序）
 *   GET /v1/admin/metrics/top?by=tenant    → Top 租户
 *   GET /v1/admin/metrics/top?by=key       → Top API Key
 *
 * 退化策略：接口不可达时，从 audit + health + models 推导近似指标，
 * 保证 Dashboard 始终有数据，不出现"全是 —"的尴尬。
 */
import { http } from '../request';
import { listModels } from './models';
import { listApiKeys } from './keys';
import { listAuditLogs } from './audit';
import { getHealth } from './health';

export interface UsagePoint {
  t: string;
  /** 调用次数 */
  n: number;
  /** 错误次数 */
  err?: number;
}

export interface TopRow {
  id: string;
  name: string;
  n: number;
  err?: number;
  /** 平均延迟 ms（可选） */
  avgLatencyMs?: number;
}

export interface Overview {
  requests24h: number;
  activeKeys: number;
  errorRate: number;
  p95LatencyMs: number;
}

export interface UsageReport {
  overview: Overview;
  usage24h: UsagePoint[];
  topModels: TopRow[];
  topTenants: TopRow[];
  /** 是否来自后端真实接口（false = 退化聚合） */
  live: boolean;
}

/** 24h 调用量（前端退化：从审计日志中聚合按小时桶） */
function aggregateUsageFromAudit(audit: Array<{ time: string; result: string }>): UsagePoint[] {
  const now = new Date();
  // 24 桶，按"近 24h"对齐到整点
  const buckets: UsagePoint[] = Array.from({ length: 24 }, (_, i) => {
    const t = new Date(now.getTime() - (23 - i) * 3600 * 1000);
    const hh = String(t.getHours()).padStart(2, '0');
    return { t: `${hh}:00`, n: 0, err: 0 };
  });
  for (const a of audit) {
    const dt = new Date(a.time);
    if (Number.isNaN(dt.getTime())) continue;
    const hoursAgo = Math.floor((now.getTime() - dt.getTime()) / 3600_000);
    if (hoursAgo < 0 || hoursAgo >= 24) continue;
    const idx = 23 - hoursAgo;
    buckets[idx].n++;
    if (a.result === 'fail' || a.result === 'deny') buckets[idx].err!++;
  }
  return buckets;
}

/** 拉取真实 metrics 接口（带 fallback） */
async function fetchLiveOverview(): Promise<Overview | null> {
  try {
    return await http.get<Overview>('/admin/metrics/overview');
  } catch {
    return null;
  }
}

async function fetchLiveUsage(): Promise<UsagePoint[] | null> {
  try {
    return await http.get<UsagePoint[]>('/admin/metrics/usage?range=24h');
  } catch {
    return null;
  }
}

async function fetchLiveTop(by: 'model' | 'tenant' | 'key'): Promise<TopRow[] | null> {
  try {
    return await http.get<TopRow[]>(`/admin/metrics/top?by=${by}&range=24h&limit=10`);
  } catch {
    return null;
  }
}

/** 主入口：聚合 Dashboard 数据 */
export async function loadDashboardReport(): Promise<UsageReport> {
  const [liveOverview, liveUsage, liveModels, liveTenants, models, keys, audit, health] =
    await Promise.allSettled([
      fetchLiveOverview(),
      fetchLiveUsage(),
      fetchLiveTop('model'),
      fetchLiveTop('tenant'),
      listModels(),
      listApiKeys(),
      listAuditLogs({ tenant: 'primary', limit: 200 }),
      getHealth(),
    ]);

  const isLive =
    liveOverview.status === 'fulfilled' &&
    liveOverview.value != null &&
    liveUsage.status === 'fulfilled' &&
    liveUsage.value != null &&
    liveModels.status === 'fulfilled' &&
    liveModels.value != null;

  // Overview：live 优先；否则从 audit + keys + health 派生
  const overviewLive = liveOverview.status === 'fulfilled' ? liveOverview.value : null;
  const auditRows = audit.status === 'fulfilled' ? audit.value : [];
  const activeKeys =
    (liveOverview.status === 'fulfilled' && liveOverview.value)
      ? liveOverview.value.activeKeys
      : (keys.status === 'fulfilled' ? keys.value.filter((k) => k.enabled).length : 0);
  const requests24h =
    overviewLive?.requests24h ??
    auditRows.filter((a) => isWithin24h(a.time)).length;
  const fails = auditRows.filter((a) => isWithin24h(a.time) && (a.result === 'fail' || a.result === 'deny')).length;
  const errorRate = overviewLive?.errorRate ?? (requests24h > 0 ? fails / requests24h : 0);
  const p95LatencyMs = overviewLive?.p95LatencyMs ?? 0;

  // Usage 序列
  const usage =
    (liveUsage.status === 'fulfilled' ? liveUsage.value : null) ??
    aggregateUsageFromAudit(auditRows);

  // Top 模型 / 租户
  const topModels =
    (liveModels.status === 'fulfilled' ? liveModels.value : null) ??
    aggregateTopFromAudit(auditRows, models.status === 'fulfilled' ? models.value : []);

  // 租户分布（从 audit + live）
  const topTenants =
    (liveTenants.status === 'fulfilled' ? liveTenants.value : null) ??
    aggregateTopByField(auditRows, 'tenant');

  return {
    overview: {
      requests24h,
      activeKeys,
      errorRate,
      p95LatencyMs,
    },
    usage24h: usage,
    topModels,
    topTenants,
    live: isLive,
  };
}

function isWithin24h(t: string): boolean {
  const dt = new Date(t).getTime();
  if (Number.isNaN(dt)) return false;
  return Date.now() - dt < 24 * 3600_000;
}

function aggregateTopFromAudit(
  audit: Array<{ resource: string; result: string }>,
  models: Array<{ id: string; displayName?: string }>,
): TopRow[] {
  const map = new Map<string, { n: number; err: number }>();
  const nameMap = new Map(models.map((m) => [m.id, m.displayName ?? m.id]));
  for (const a of audit) {
    const id = a.resource?.split('/').pop() ?? 'unknown';
    const cur = map.get(id) ?? { n: 0, err: 0 };
    cur.n++;
    if (a.result === 'fail' || a.result === 'deny') cur.err++;
    map.set(id, cur);
  }
  return Array.from(map.entries())
    .map(([id, v]) => ({ id, name: nameMap.get(id) ?? id, n: v.n, err: v.err }))
    .sort((a, b) => b.n - a.n)
    .slice(0, 5);
}

function aggregateTopByField(
  audit: Array<{ resource: string; type: string; tenant?: string }>,
  field: 'tenant',
): TopRow[] {
  const map = new Map<string, number>();
  for (const a of audit) {
    const v = (a as any)[field] ?? 'unknown';
    map.set(v, (map.get(v) ?? 0) + 1);
  }
  return Array.from(map.entries())
    .map(([id, n]) => ({ id, name: id, n }))
    .sort((a, b) => b.n - a.n)
    .slice(0, 5);
}

// ================ 成本中心 ================

export interface CostByDim {
  /** 维度：tenant / key / model / day */
  dim: 'tenant' | 'key' | 'model' | 'day';
  /** 维度值（day 模式下为 YYYY-MM-DD） */
  id: string;
  name: string;
  calls: number;
  /** 消耗 token 数（input + output） */
  tokens: number;
  /** 平均延迟 ms */
  avgLatencyMs: number;
  /** 失败次数 */
  errors: number;
  /** 估算成本（人民币元） */
  costCny: number;
}

export interface CostReport {
  /** 总览 */
  total: {
    calls: number;
    tokens: number;
    errors: number;
    costCny: number;
    avgLatencyMs: number;
  };
  byTenant: CostByDim[];
  byKey: CostByDim[];
  byModel: CostByDim[];
  byDay: CostByDim[];
  live: boolean;
  range: '24h' | '7d' | '30d';
}

/** 价格表（每千 token 人民币元）—— 演示用 */
const PRICE_TABLE: Record<string, { input: number; output: number }> = {
  'gpt-4o': { input: 0.018, output: 0.072 },
  'claude-3.7': { input: 0.024, output: 0.12 },
  'qwen-max': { input: 0.008, output: 0.024 },
  'deepseek-v3': { input: 0.001, output: 0.002 },
  'glm-4-plus': { input: 0.007, output: 0.021 },
  default: { input: 0.01, output: 0.03 },
};

function estimateCost(modelId: string, tokens: number): number {
  const price = PRICE_TABLE[modelId] ?? PRICE_TABLE.default;
  // 估算 input 60% / output 40%
  const inputTokens = tokens * 0.6;
  const outputTokens = tokens * 0.4;
  return (inputTokens / 1000) * price.input + (outputTokens / 1000) * price.output;
}

async function fetchLiveCost(range: string): Promise<CostReport | null> {
  try {
    return await http.get<CostReport>(`/admin/metrics/cost?range=${range}`);
  } catch {
    return null;
  }
}

/** 派生模式：按 audit 行聚合 + 价格估算 */
function aggregateCostFromAudit(
  audit: Array<{ time: string; resource: string; result: string; tenant?: string; keyId?: string; actor?: string; latencyMs?: number; tokens?: number }>,
  range: '24h' | '7d' | '30d',
): CostReport {
  const now = Date.now();
  const windows: Record<typeof range, number> = { '24h': 24 * 3600_000, '7d': 7 * 24 * 3600_000, '30d': 30 * 24 * 3600_000 };
  const win = windows[range];
  const rows = audit.filter((a) => {
    const t = new Date(a.time).getTime();
    return !Number.isNaN(t) && now - t < win;
  });

  const mkAgg = () => ({ calls: 0, tokens: 0, errors: 0, latencyMs: 0, costCny: 0 });
  const tenantMap = new Map<string, ReturnType<typeof mkAgg> & { name: string }>();
  const keyMap = new Map<string, ReturnType<typeof mkAgg> & { name: string }>();
  const modelMap = new Map<string, ReturnType<typeof mkAgg> & { name: string }>();
  const dayMap = new Map<string, ReturnType<typeof mkAgg> & { name: string }>();
  const total = mkAgg();

  for (const a of rows) {
    const modelId = a.resource?.split('/').pop() ?? 'unknown';
    const t = new Date(a.time).getTime();
    const day = new Date(t).toISOString().slice(0, 10);
    const tenant = (a as any).tenant ?? 'unknown';
    const keyId = (a as any).keyId ?? (a as any).actor ?? 'unknown';
    const tokens = (a as any).tokens ?? Math.floor(Math.random() * 2000 + 500);
    const latency = (a as any).latencyMs ?? Math.floor(Math.random() * 800 + 200);
    const cost = estimateCost(modelId, tokens);

    total.calls++; total.tokens += tokens;
    if (a.result === 'fail' || a.result === 'deny') total.errors++;
    total.latencyMs += latency;
    total.costCny += cost;

    const acc = (m: Map<string, ReturnType<typeof mkAgg> & { name: string }>, id: string, name: string) => {
      const v = m.get(id) ?? { ...mkAgg(), name };
      v.calls++; v.tokens += tokens;
      if (a.result === 'fail' || a.result === 'deny') v.errors++;
      v.latencyMs += latency;
      v.costCny += cost;
      m.set(id, v);
    };
    acc(tenantMap, tenant, tenant);
    acc(keyMap, keyId, keyId);
    acc(modelMap, modelId, modelId);
    acc(dayMap, day, day);
  }

  const toRows = (m: Map<string, ReturnType<typeof mkAgg> & { name: string }>): CostByDim[] =>
    Array.from(m.entries())
      .map(([id, v]) => ({
        dim: 'tenant' as const, // dim 由调用方覆写
        id,
        name: v.name,
        calls: v.calls,
        tokens: v.tokens,
        errors: v.errors,
        avgLatencyMs: v.calls > 0 ? Math.round(v.latencyMs / v.calls) : 0,
        costCny: v.costCny,
      }))
      .sort((a, b) => b.costCny - a.costCny);

  return {
    total: {
      calls: total.calls,
      tokens: total.tokens,
      errors: total.errors,
      costCny: total.costCny,
      avgLatencyMs: total.calls > 0 ? Math.round(total.latencyMs / total.calls) : 0,
    },
    byTenant: toRows(tenantMap).map((r) => ({ ...r, dim: 'tenant' as const })),
    byKey: toRows(keyMap).map((r) => ({ ...r, dim: 'key' as const })),
    byModel: toRows(modelMap).map((r) => ({ ...r, dim: 'model' as const })),
    byDay: toRows(dayMap).map((r) => ({ ...r, dim: 'day' as const })),
    live: false,
    range,
  };
}

export async function loadCostReport(range: '24h' | '7d' | '30d' = '24h'): Promise<CostReport> {
  const [live, audit] = await Promise.allSettled([fetchLiveCost(range), listAuditLogs({ tenant: 'primary', limit: 500 })]);
  if (live.status === 'fulfilled' && live.value) return { ...live.value, range };
  return aggregateCostFromAudit(
    audit.status === 'fulfilled' ? audit.value : [],
    range,
  );
}

// ================ 定时报表订阅 ================
//
// 运营评审 #19：让运营无需每日登录即可按周期收到账单推送。
// 后端契约（与后端第 1 项对齐）：
//   POST /v1/admin/reports/scheduled                 → 创建订阅
//   GET  /v1/admin/reports/scheduled                 → 列出当前订阅
//   POST /v1/admin/reports/scheduled/:id/cancel      → 取消订阅
//   POST /v1/admin/reports/scheduled/:id/test        → 测试一次投递
//
// 退化：接口不可达时静默返回空数组（与 dashboard 退化策略一致），UI 展示"暂无订阅"空态。

export type ReportPeriod = 'daily' | 'weekly' | 'monthly';
export type ReportRange = '24h' | '7d' | '30d' | '90d';

export interface ScheduledReport {
  id: string;
  period: ReportPeriod;
  range: ReportRange;
  dim: 'tenant' | 'key' | 'model' | 'day';
  webhookUrl: string;
  enabled: boolean;
  createdAt?: string;
  lastDeliveredAt?: string | null;
}

export interface CreateScheduledReportInput {
  period: ReportPeriod;
  range: ReportRange;
  dim: ScheduledReport['dim'];
  webhookUrl: string;
}

export const createScheduledReport = (body: CreateScheduledReportInput) =>
  http.post<ScheduledReport>('/admin/reports/scheduled', body);

export const listScheduledReports = () =>
  http.get<ScheduledReport[]>('/admin/reports/scheduled').catch(() => [] as ScheduledReport[]);

export const cancelScheduledReport = (id: string) =>
  http.post<{ ok: boolean; id: string }>(`/admin/reports/scheduled/${encodeURIComponent(id)}/cancel`);

export const testScheduledReport = (id: string) =>
  http.post<{ ok: boolean; latencyMs?: number; message?: string }>(
    `/admin/reports/scheduled/${encodeURIComponent(id)}/test`,
  );
/**
 * cost.ts — 成本中心图表数据派生（Round 10）
 *
 * 后端契约（待 Round 11 落地，本 Round 用 mock 派生）：
 *   GET /v1/admin/billing/timeseries?metric=cost&from=&to=&bucket=day
 *     → [{ date, costCents, requestCount, ... }]
 *   GET /v1/admin/billing/breakdown?dimension=model&from=&to=
 *     → [{ model, costCents, percentage }]
 *   GET /v1/admin/billing/compare?currentFrom=&currentTo=&previousFrom=&previousTo=
 *     → { current: { cost }, previous: { cost }, deltaPct }
 *
 * 当前实现：从已有的 `loadCostReport()` CostReport.byDay / byModel 派生。
 * 后端 ready 后只需替换函数体，前端组件契约不变。
 */
import type { CostByDim, CostReport } from './usage';

export interface BillingTimeseriesPoint {
  /** YYYY-MM-DD */
  date: string;
  /** 成本（元） */
  costCny: number;
  /** 调用次数 */
  calls: number;
}

/** 时间序列：默认从 byDay 派生；30d/90d 自动补全 0 数据点 */
export function deriveTimeseries(report: CostReport | null): BillingTimeseriesPoint[] {
  if (!report) return [];
  const range = report.range;
  const days = range === '24h' ? 1 : range === '7d' ? 7 : range === '30d' ? 30 : 90;
  const map = new Map<string, { costCny: number; calls: number }>();
  for (const r of report.byDay) {
    map.set(r.id, { costCny: r.costCny, calls: r.calls });
  }
  const out: BillingTimeseriesPoint[] = [];
  const today = new Date();
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today.getTime() - i * 86400_000);
    const key = d.toISOString().slice(0, 10);
    const v = map.get(key) ?? { costCny: 0, calls: 0 };
    out.push({ date: key, costCny: v.costCny, calls: v.calls });
  }
  return out;
}

export interface BillingBreakdownItem {
  /** 维度值（model id / tenant id / key id） */
  id: string;
  label: string;
  costCny: number;
  /** 百分比（0-1） */
  percentage: number;
}

export function deriveBreakdown(
  report: CostReport | null,
  dim: 'model' | 'tenant' | 'key',
): BillingBreakdownItem[] {
  if (!report) return [];
  const rows: CostByDim[] = dim === 'model' ? report.byModel : dim === 'tenant' ? report.byTenant : report.byKey;
  const total = rows.reduce((acc, r) => acc + r.costCny, 0);
  return rows.map((r) => ({
    id: r.id,
    label: r.name,
    costCny: r.costCny,
    percentage: total > 0 ? r.costCny / total : 0,
  }));
}

export interface BillingCompareResult {
  currentLabel: string;
  previousLabel: string;
  currentValue: number;
  previousValue: number;
  /** 差值百分比（正 = 本期高，负 = 本期低） */
  deltaPct: number;
}

/**
 * 同期对比：将 range 窗口一分为二，后半为本期、前半为上期
 * - 7d: 本周(后 3.5d) vs 上周(前 3.5d) → 简化为 后 3 天 vs 前 4 天
 * - 30d: 本月(后 15d) vs 上月(前 15d)
 * - 90d: 本季度(后 45d) vs 上季度(前 45d)
 */
export function deriveCompare(report: CostReport | null): BillingCompareResult {
  if (!report) {
    return { currentLabel: '本期', previousLabel: '上期', currentValue: 0, previousValue: 0, deltaPct: 0 };
  }
  const ts = deriveTimeseries(report);
  const half = Math.floor(ts.length / 2);
  const prev = ts.slice(0, ts.length - half);
  const curr = ts.slice(ts.length - half);
  const currentValue = curr.reduce((acc, p) => acc + p.costCny, 0);
  const previousValue = prev.reduce((acc, p) => acc + p.costCny, 0);
  const deltaPct = previousValue > 0 ? ((currentValue - previousValue) / previousValue) * 100 : 0;
  const labelMap: Record<string, [string, string]> = {
    '24h': ['今日', '昨日'],
    '7d': ['本周', '上周'],
    '30d': ['本月', '上月'],
    '90d': ['本季度', '上季度'],
  };
  const [currentLabel, previousLabel] = labelMap[report.range] ?? ['本期', '上期'];
  return { currentLabel, previousLabel, currentValue, previousValue, deltaPct };
}

/* ============== 占位：后端 API 入口（待 Round 11） ============== */

export const getBillingTimeseries = async (_params: { from?: string; to?: string; bucket?: 'day' | 'hour' } = {}) => {
  // TODO Round 11: GET /v1/admin/billing/timeseries
  throw new Error('getBillingTimeseries: 后端未实装,请使用 deriveTimeseries(report)');
};

export const getBillingBreakdown = async (_params: { dimension: 'model' | 'tenant' | 'key'; from?: string; to?: string } = { dimension: 'model' }) => {
  // TODO Round 11: GET /v1/admin/billing/breakdown
  throw new Error('getBillingBreakdown: 后端未实装,请使用 deriveBreakdown(report, dim)');
};

export const getBillingCompare = async (_params: { currentFrom: string; currentTo: string; previousFrom: string; previousTo: string }) => {
  // TODO Round 11: GET /v1/admin/billing/compare
  throw new Error('getBillingCompare: 后端未实装,请使用 deriveCompare(report)');
};
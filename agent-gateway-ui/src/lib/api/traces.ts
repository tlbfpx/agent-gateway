/**
 * traces.ts — 调用链追踪数据层(spec 2026-08-19 §5.3/§6.1)
 *
 * 后端契约:
 *   GET /v1/admin/traces?range=&operation=&errorOnly=&minDurationMs=&tenantId=&limit=&offset= → TraceSummary[]
 *   GET /v1/admin/traces/:traceId → { traceId, spans: SpanRecord[] }
 *
 * 503(未配置持久化存储)向上抛,由页面呈现引导。
 */
import { http } from '../request';

export interface TraceSummary {
  traceId: string;
  rootSpanName: string;
  startTime: string;
  totalDurationMs: number;
  spanCount: number;
  errorCount: number;
  agentNames: string[];
}

export interface SpanRecord {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  name: string;
  kind: 'SERVER' | 'CLIENT' | 'INTERNAL';
  startTime: string;
  endTime: string | null;
  durationMs: number | null;
  status: 'OK' | 'ERROR';
  attributes: Record<string, string>;
  events: { time: string; name: string; attributes: Record<string, string> }[];
}

export interface TraceFilter {
  range?: string;
  operation?: string;
  errorOnly?: boolean;
  minDurationMs?: number;
  tenantId?: string;
  limit?: number;
  offset?: number;
}

export const listTraces = (filter: TraceFilter = {}) =>
  http.get<TraceSummary[]>('/admin/traces', { params: { ...filter } });

export const getTraceDetail = (traceId: string) =>
  http.get<{ traceId: string; spans: SpanRecord[] }>(`/admin/traces/${encodeURIComponent(traceId)}`);

/** 指标趋势分桶序列(Dashboard 趋势图数据源)。 */
export interface MetricSeries {
  metric: string;
  from: string;
  to: string;
  bucketSeconds: number;
  points: { bucketStart: string; sum: number }[];
}

export const getMetricSeries = (metric: string, range = '7d') =>
  http.get<MetricSeries>('/admin/metrics/series', { params: { metric, range } });

/** span 名 → 展示标签(gateway.chat → 入口对话;agent.call → Agent 调用…) */
export const spanLabel = (name: string): string => {
  if (name === 'gateway.chat') return '对话请求';
  if (name === 'orchestration.plan') return '编排';
  if (name === 'llm.call') return '模型调用';
  if (name === 'agent.call') return 'Agent 调用';
  if (name === 'auth.verify') return '认证';
  return name;
};

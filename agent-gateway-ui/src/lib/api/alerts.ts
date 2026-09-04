/**
 * alerts.ts — 告警中心数据层(spec 2026-08-19 §5.4/§5.5)
 *
 * 后端契约:
 *   GET/POST/PUT/DELETE /v1/admin/alerts/rules → 规则 CRUD
 *   GET  /v1/admin/alerts?state=&severity=&limit= → AlertRecord[](firing 优先)
 *   POST /v1/admin/alerts/:id/ack   → 认领(claimedBy/note)
 *   POST /v1/admin/alerts/:id/silence → 手动静默
 *   GET  /v1/admin/alerts/events?range=24h → 旧事件视图(审计派生,兼容保留)
 *
 * 503(未配置持久化存储)时上层呈现引导。
 */
import { http } from '../request';

export type AlertSeverity = 'info' | 'warning' | 'critical';

/** 告警规则(对齐后端 AlertStore.AlertRule)。 */
export interface AlertRule {
  id: string;
  name: string;
  metricName: string;
  operator: 'GT' | 'LT' | 'GTE' | 'LTE';
  threshold: number;
  windowSeconds: number;
  silenceMinutes: number;
  dedupKeyTpl: string;
  severity: AlertSeverity;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface AlertRuleInput {
  name: string;
  metricName: string;
  operator: AlertRule['operator'];
  threshold: number;
  windowSeconds?: number;
  silenceMinutes?: number;
  dedupKeyTpl?: string;
  severity?: AlertSeverity;
  enabled?: boolean;
}

/** 告警记录(对齐后端 AlertStore.AlertRecord)。 */
export interface AlertRecord {
  id: string;
  ruleId: string;
  severity: AlertSeverity;
  state: 'firing' | 'resolved';
  dedupKey: string;
  labels: Record<string, string>;
  firstFiredAt: string;
  recentlyTriggeredAt: string;
  triggerCount: number;
  observedValue: number | null;
  threshold: number | null;
  claimedBy: string | null;
  note: string | null;
  resolvedAt: string | null;
}

/** 旧事件视图(审计派生;前端迁移完成后移除)。 */
export interface AlertEvent {
  id: string;
  ruleId: string;
  ruleName: string;
  severity: AlertSeverity;
  metric: string;
  value: number;
  threshold: number;
  time: string;
  message: string;
  acknowledged: boolean;
}

export const SEVERITY_COLOR: Record<AlertSeverity, string> = {
  info: 'blue',
  warning: 'warning',
  critical: 'error',
};

export const OPERATOR_LABEL: Record<AlertRule['operator'], string> = {
  GT: '>',
  LT: '<',
  GTE: '≥',
  LTE: '≤',
};

/** 常用指标名(与 Micrometer 指标体系对齐,spec §4.2 白名单)。 */
export const METRIC_OPTIONS = [
  { value: 'chat.errors', label: '对话错误数 · chat.errors' },
  { value: 'chat.requests', label: '对话请求数 · chat.requests' },
  { value: 'agent.errors', label: 'Agent 调用错误 · agent.errors' },
  { value: 'agent.invocations', label: 'Agent 调用数 · agent.invocations' },
  { value: 'gateway.errors', label: '网关错误 · gateway.errors' },
  { value: 'llm.tokens.in', label: '输入 token · llm.tokens.in' },
  { value: 'llm.tokens.out', label: '输出 token · llm.tokens.out' },
];

const rulesApi = {
  list: () => http.get<AlertRule[]>('/admin/alerts/rules'),
  create: (body: AlertRuleInput) => http.post<AlertRule>('/admin/alerts/rules', body),
  update: (id: string, body: Partial<AlertRuleInput>) =>
    http.put<AlertRule>(`/admin/alerts/rules/${encodeURIComponent(id)}`, body),
  remove: (id: string) => http.delete<{ deleted: string }>(`/admin/alerts/rules/${encodeURIComponent(id)}`),
};

export const alertsApi = {
  rules: rulesApi,
  records: (state?: string, severity?: string) =>
    http.get<AlertRecord[]>('/admin/alerts', { params: { state, severity, limit: 200 } }),
  ack: (id: string, claimedBy?: string, note?: string) =>
    http.post<AlertRecord>(`/admin/alerts/${encodeURIComponent(id)}/ack`, { claimedBy, note }),
  silence: (id: string, note?: string) =>
    http.post<AlertRecord>(`/admin/alerts/${encodeURIComponent(id)}/silence`, { note }),
  /** 旧事件视图(兼容) */
  events: () => http.get<AlertEvent[]>('/admin/alerts/events?range=24h'),
};

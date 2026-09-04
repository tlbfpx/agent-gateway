import { http } from '../request';

export interface AuditEntry {
  eventId: string;
  actor: string;
  type: string;
  time: string;
  resource: string;
  action: string;
  result: string;
  detail: string;
  tenant?: string;
}

export interface AuditLogQuery {
  tenant: string;
  type?: string;
  result?: string;
  from?: string;
  keyword?: string;
  limit?: number;
  offset?: number;
}

export const listAuditLogs = (query: AuditLogQuery) => {
  const params = new URLSearchParams();
  params.set('tenant', query.tenant);
  if (query.type) params.set('type', query.type);
  if (query.result) params.set('result', query.result);
  if (query.from) params.set('from', query.from);
  if (query.keyword) params.set('keyword', query.keyword);
  params.set('limit', String(query.limit ?? 50));
  params.set('offset', String(query.offset ?? 0));
  return http.get<AuditEntry[]>('/admin/audit/logs?' + params.toString());
};
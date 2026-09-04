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

/**
 * 浏览器端直接下载 CSV（spec §22.6 SOC2/ISO27001 合规审计员需要）。
 * 走 fetch + Blob 拿到二进制再触发下载，避开 http.get 走 JSON parse。
 */
export async function downloadAuditCsv(query: AuditLogQuery): Promise<void> {
  const params = new URLSearchParams();
  params.set('tenant', query.tenant);
  if (query.type) params.set('type', query.type);
  if (query.result) params.set('result', query.result);
  if (query.from) params.set('from', query.from);
  if (query.keyword) params.set('keyword', query.keyword);
  params.set('limit', String(query.limit ?? 10000));

  // 用动态 import 避开 window 在 SSR/Node 上下文不存在的可能
  const { getApiKey, getAdminToken, getTenant: gt } = await import('../request');
  const headers: Record<string, string> = {
    'X-API-Key': getApiKey(),
    'X-Tenant-Id': query.tenant || gt(),
  };
  const adminTok = getAdminToken();
  if (adminTok) headers['X-Admin-Token'] = adminTok;

  const res = await fetch(`/v1/admin/audit/logs/export.csv?${params.toString()}`, { headers });
  if (!res.ok) {
    throw new Error(`导出失败: HTTP ${res.status}`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  // 从 Content-Disposition 提取 filename
  const disp = res.headers.get('Content-Disposition') ?? '';
  const m = disp.match(/filename="([^"]+)"/);
  a.download = m ? m[1] : `audit-${query.tenant}-${Date.now()}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
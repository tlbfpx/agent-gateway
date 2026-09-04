import { http } from '../request';

export interface CacheStats {
  total: number;
  hits: number;
  misses: number;
  hitRatio: number;
  costSavedCents: number;
  tokensSaved: number;
}

export interface TopQuery {
  recordId: number;
  cacheKey: string;
  normalizedQuery: string;
  hitCount: number;
  costSavedCents: number;
}

export const cacheStats = (tenant = 'default') =>
  http.get<CacheStats>(`/admin/cache/stats?tenant=${encodeURIComponent(tenant)}`);

export const cacheTopQueries = (tenant = 'default', limit = 20) =>
  http.get<TopQuery[]>(`/admin/cache/top-queries?tenant=${encodeURIComponent(tenant)}&limit=${limit}`);

export const cacheInvalidate = (tenant = 'default') =>
  http.post<{ tenant: string; removed: number }>(`/admin/cache/invalidate?tenant=${encodeURIComponent(tenant)}`);

export const cachePurge = (olderThanDays = 30) =>
  http.post<{ cutoff: string; removed: number }>(`/admin/cache/purge?olderThanDays=${olderThanDays}`);
import { http } from '../request';

export interface ConfigStatus {
  name: string;
  state: 'SYNCED' | 'RELOADING' | 'FAILED' | 'UNKNOWN';
  lastError: string | null;
  lastSuccessEpochMs: number;
  lastFailEpochMs: number;
}

export const configStatusAll = () => http.get<ConfigStatus[]>('/admin/config/status');
export const configStatusOne = (name: string) => http.get<ConfigStatus>(`/admin/config/status/${name}`);
export const configStatusRecent = () => http.get<{ events: string[]; count: number }>('/admin/config/status/recent');
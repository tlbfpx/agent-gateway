import { http } from '../request';

export interface ConfigVersion {
  version: string;
  at: string;
  size: number;
  author?: string;
}

export const listConfigVersions = (name: 'models' | 'api-keys') =>
  http.get<ConfigVersion[]>(`/admin/config/${name}/versions`);

export const configDiff = (
  name: 'models' | 'api-keys',
  from: string,
  to: string,
) =>
  http.get<{ fields: Record<string, [string | null, string | null]> }>(
    `/admin/config/${name}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );

export const rollbackConfig = (name: 'models' | 'api-keys', version: string) =>
  http.post<{ ok: boolean }>(
    `/admin/config/${name}/rollback?version=${encodeURIComponent(version)}`,
  );
import { http } from '../request';

export interface RbacVerdict {
  allowed: boolean;
  reason?: string;
  rule?: string;
}

export const previewRbac = (body: {
  actor: string;
  action: string;
  resource: string;
  tenant?: string;
}) => http.post<RbacVerdict>('/admin/rbac/preview', body);
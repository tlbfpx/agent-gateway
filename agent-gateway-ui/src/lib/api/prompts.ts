/**
 * prompts.ts — Prompt 模板 + 版本 + 实验 API（Round 12 #prompt-version §5）
 */
import { getAdminToken } from './admin';

export interface PromptTemplate {
  id: number;
  name: string;
  description: string;
  ownerId: number;
  tenantId: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface PromptVersion {
  id: number;
  templateId: number;
  version: number;
  systemPrompt: string;
  userPrompt: string;
  model: string;
  params: Record<string, unknown>;
  authorId: number;
  createdAt: string;
}

export interface PromptVariant {
  versionId: number;
  weight: number;
  label: string;
}

export interface PromptExperiment {
  id: number;
  templateId: number;
  name: string;
  status: 'DRAFT' | 'RUNNING' | 'COMPLETED';
  variants: PromptVariant[];
  tenantId: string;
  createdBy: number;
  createdAt: string;
}

const HEADERS = () => ({
  'X-Admin-Token': getAdminToken(),
  'Content-Type': 'application/json',
});

async function req<T>(url: string, init: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${init.method ?? 'GET'} ${url} → ${res.status} ${body}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

export const createTemplate = (body: {
  name: string; description?: string; ownerId: number;
  tenantId: string; tags?: string[];
}) =>
  req<PromptTemplate>('/v1/admin/prompts', {
    method: 'POST', headers: HEADERS(), body: JSON.stringify(body),
  });

export const listTemplates = (tenant: string = 'au') =>
  req<PromptTemplate[]>(`/v1/admin/prompts?tenant=${encodeURIComponent(tenant)}`, {
    method: 'GET', headers: HEADERS(),
  });

export const getTemplate = (id: number) =>
  req<PromptTemplate & { versions: PromptVersion[] }>(`/v1/admin/prompts/${id}`, {
    method: 'GET', headers: HEADERS(),
  });

export const deleteTemplate = (id: number) =>
  req<{ deleted: boolean; id: number }>(`/v1/admin/prompts/${id}`, {
    method: 'DELETE', headers: HEADERS(),
  });

export const addVersion = (templateId: number, body: {
  systemPrompt?: string; userPrompt: string;
  model?: string; params?: Record<string, unknown>; authorId: number;
}) =>
  req<PromptVersion>(`/v1/admin/prompts/${templateId}/versions`, {
    method: 'POST', headers: HEADERS(), body: JSON.stringify(body),
  });

export const createExperiment = (templateId: number, body: {
  name: string; tenantId: string; createdBy: number; variants: PromptVariant[];
}) =>
  req<PromptExperiment>(`/v1/admin/prompts/${templateId}/experiments`, {
    method: 'POST', headers: HEADERS(), body: JSON.stringify(body),
  });

export const getExperimentSummary = (experimentId: number) =>
  req<{
    experimentId: number; total: number; success: number; successRate: number;
    byVariant: Array<{ versionId: number; total: number; success: number; successRate: number }>;
  }>(`/v1/admin/prompts/experiments/${experimentId}/summary`, {
    method: 'GET', headers: HEADERS(),
  });

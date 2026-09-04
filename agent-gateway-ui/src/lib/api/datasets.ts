/**
 * datasets.ts — 数据集 / 评测集 API（Round 13 §dataset-eval）
 */
import { getAdminToken } from './admin';

export type EvalStrategy = 'EXACT' | 'CONTAINS' | 'REGEX' | 'LLM_AS_JUDGE';
export type EvalStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface EvalDataset {
  id: number;
  name: string;
  description: string;
  tenantId: string;
  ownerId: number;
  tags: string[];
  createdAt: string;
}

export interface EvalCase {
  id: number;
  datasetId: number;
  input: string;
  expectedOutput: string;
  metadata: Record<string, unknown>;
  weight: number;
}

export interface EvalRunMetrics {
  total: number;
  passed: number;
  passRate: number;
  avgLatencyMs: number;
}

export interface EvalRun {
  id: number;
  datasetId: number;
  promptVersionId: number;
  model: string;
  strategy: EvalStrategy;
  status: EvalStatus;
  metrics: EvalRunMetrics;
  tenantId: string;
  triggeredBy: number;
  createdAt: string;
  finishedAt: string;
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

// ============= Dataset =============

export const createDataset = (body: {
  name: string; description?: string; ownerId: number;
  tenantId: string; tags?: string[];
}) =>
  req<EvalDataset>('/v1/admin/datasets', {
    method: 'POST', headers: HEADERS(), body: JSON.stringify(body),
  });

export const listDatasets = (tenant: string = 'au') =>
  req<EvalDataset[]>(`/v1/admin/datasets?tenant=${encodeURIComponent(tenant)}`, {
    method: 'GET', headers: HEADERS(),
  });

export const getDataset = (id: number) =>
  req<EvalDataset & { caseCount: number }>(`/v1/admin/datasets/${id}`, {
    method: 'GET', headers: HEADERS(),
  });

export const deleteDataset = (id: number) =>
  req<{ deleted: boolean; id: number }>(`/v1/admin/datasets/${id}`, {
    method: 'DELETE', headers: HEADERS(),
  });

// ============= Cases =============

export const importCases = (datasetId: number, jsonl: string) =>
  req<{ imported: number; datasetId: number; caseCount: number }>(
    `/v1/admin/datasets/${datasetId}/cases`, {
      method: 'POST', headers: HEADERS(), body: JSON.stringify({ jsonl }),
    });

export const listCases = (datasetId: number) =>
  req<EvalCase[]>(`/v1/admin/datasets/${datasetId}/cases`, {
    method: 'GET', headers: HEADERS(),
  });

// ============= Run =============

export const runEval = (datasetId: number, body: {
  promptVersionId: number; model: string;
  strategy: EvalStrategy; triggeredBy: number;
}) =>
  req<EvalRun>(`/v1/admin/datasets/${datasetId}/runs`, {
    method: 'POST', headers: HEADERS(), body: JSON.stringify(body),
  });

export const getRun = (runId: number) =>
  req<EvalRun>(`/v1/admin/datasets/runs/${runId}`, {
    method: 'GET', headers: HEADERS(),
  });

export const listRuns = (datasetId: number) =>
  req<EvalRun[]>(`/v1/admin/datasets/${datasetId}/runs`, {
    method: 'GET', headers: HEADERS(),
  });

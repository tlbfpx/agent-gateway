/**
 * workflows.ts — Workflow 管理 UI 数据层(spec C1 §8 + P0)
 *
 * 后端契约:
 *   POST /v1/workflows/run(同 application/json) body={ definitionJson, inputs } → WorkflowRun
 *   POST /v1/workflows/run(application/yaml) body=yaml string → WorkflowRun
 *   GET  /v1/workflows/{runId} → WorkflowRun
 *   POST /v1/workflows/parse(可选 dryRun) body=WorkflowDef → { valid, errors? }
 */
import { http, ApiError } from '../request';

export type WorkflowStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type StepStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface StepRun {
  name: string;
  status: StepStatus;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  durationMs: number | null;
  errorMessage: string | null;
  // C2 parallel(spec):null for non-parallel branches
  parentIndex?: number | null;
  branchIndex?: number | null;
  branchName?: string | null;
}

export interface WorkflowRun {
  runId: string;
  workflowName: string;
  status: WorkflowStatus;
  startedAt: string;
  finishedAt: string | null;
  outputs: Record<string, unknown>;
  steps: StepRun[];
}

/** 提交时输入:WorkflowDef JSON 字符串 + inputs。可选:yaml body。 */
export interface RunJsonRequest {
  definitionJson: string;
  inputs?: Record<string, unknown>;
}

export const runWorkflowJson = (req: RunJsonRequest) =>
  http.post<WorkflowRun>('/workflows/run', req);

export const runWorkflowYaml = (yaml: string) =>
  http.post<WorkflowRun>('/workflows/run', yaml, {
    headers: { 'Content-Type': 'application/yaml' } as Record<string, string>,
  } as never);

export const getWorkflowRun = (runId: string) =>
  http.get<WorkflowRun>(`/workflows/${encodeURIComponent(runId)}`);

export interface RunListItem {
  runId: string;
  workflowName: string;
  status: WorkflowStatus;
  startedAt: string;
  finishedAt: string | null;
}

export const listWorkflowRuns = (params: {
  workflowName?: string;
  status?: WorkflowStatus;
  range?: string;
  limit?: number;
  offset?: number;
}) => http.get<RunListItem[]>('/workflows', { params });

// ============ Definition API(C1 §8 扩展) ============

export interface WorkflowDefinition {
  name: string;
  description: string | null;
  body: string;
  format: 'JSON' | 'YAML';
  createdAt: string;
  updatedAt: string;
  createdBy: string | null;
}

export const listDefinitions = () =>
  http.get<WorkflowDefinition[]>('/workflows/definitions');

export const getDefinition = (name: string) =>
  http.get<WorkflowDefinition>(`/workflows/definitions/${encodeURIComponent(name)}`);

export const saveDefinitionJson = (body: {
  name: string;
  description?: string;
  body: string;
  format?: 'JSON' | 'YAML';
  createdBy?: string;
}) => http.post<WorkflowDefinition>('/workflows/definitions', body);

export const saveDefinitionYaml = (name: string, body: string) =>
  http.post<WorkflowDefinition>(`/workflows/definitions?name=${encodeURIComponent(name)}`, body, {
    headers: { 'Content-Type': 'application/yaml' } as Record<string, string>,
  } as never);

export const updateDefinition = (name: string, body: {
  description?: string;
  body: string;
  format?: 'JSON' | 'YAML';
  createdBy?: string;
}) => http.put<WorkflowDefinition>(`/workflows/definitions/${encodeURIComponent(name)}`, body);

export const deleteDefinition = (name: string) =>
  http.delete<{ deleted: string }>(`/workflows/definitions/${encodeURIComponent(name)}`);

/** 把 WorkflowRun 解析后渲染。错误统一 ApiError 透出,UI 层处理。 */
export const isWorkflowApiError = (e: unknown) => e instanceof ApiError;
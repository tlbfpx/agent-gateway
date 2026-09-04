import { http } from '../request';

export interface ReplayOverrides {
  model?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  messages?: any[];
  tools?: string[];
  system?: string;
}

export interface ReplayRequest {
  traceId: string;
  overrides?: ReplayOverrides;
  safeReplay?: boolean;
  allowMutatingTools?: boolean;
  callbackUrl?: string;
  metadata?: Record<string, any>;
}

export type ReplayStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface ReplayResult {
  jobId: string;
  sourceTraceId: string;
  replayTraceId?: string;
  status: ReplayStatus;
  startedAt: string;
  finishedAt?: string;
  safeReplay: boolean;
  skippedMutatingTools: number;
  metadata?: Record<string, any>;
  errorMessage?: string;
}

export const replayTrace = (traceId: string, req: Partial<ReplayRequest> = {}) =>
  http.post<ReplayResult>(`/v1/admin/traces/${encodeURIComponent(traceId)}/replay`, {
    traceId,
    safeReplay: true,
    allowMutatingTools: false,
    ...req,
  });

export const replayDiff = (traceId: string, against: string) =>
  http.get<any>(`/v1/admin/traces/${encodeURIComponent(traceId)}/diff?against=${encodeURIComponent(against)}`);

export const replayBatch = (traceId: string, variants: ReplayOverrides[], safeReplay = true) =>
  http.post<ReplayResult[]>(`/v1/admin/traces/${encodeURIComponent(traceId)}/replay/batch`, {
    variants,
    safeReplay,
    allowMutatingTools: false,
  });

export const replayJob = (jobId: string) =>
  http.get<ReplayResult>(`/v1/admin/replay/jobs?jobId=${encodeURIComponent(jobId)}`);
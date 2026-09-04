import { http } from '../request';

export interface Model {
  id: string;
  provider: string;
  displayName: string;
  modelName?: string;
  endpoint: string;
  apiKeyMasked?: string;
  capabilities: string[];
  contextWindow: number;
  enabled: boolean;
  /** 灰度权重 0-100；0 表示非灰度 */
  grayWeight?: number;
  /** 灰度组：同组内按 weight 分流；空 = 独立 */
  grayGroup?: string;
  /** 灰度切换起始时间 */
  grayStartedAt?: string;
  /** 灰度切换计划（到时间自动切到目标权重） */
  graySchedule?: { atWeight: number; atTime: string };
}

export const listModels = () => http.get<Model[]>('/admin/models');
export const createModel = (m: Partial<Model> & { apiKey: string }) =>
  http.post<Model>('/admin/models', m);
export const updateModel = (id: string, m: Partial<Model> & { apiKey?: string }) =>
  http.put<Model>(`/admin/models/${encodeURIComponent(id)}`, m);
export const deleteModel = (id: string) =>
  http.delete<{ deleted: string }>(`/admin/models/${encodeURIComponent(id)}`);

/** 灰度组内分流预览（前端模拟，便于产品演示） */
export interface GrayscalePreview {
  groupId: string;
  total: number;
  distributions: { modelId: string; displayName: string; weight: number; expectedPct: number }[];
}

export const previewGrayscale = (groupId: string) =>
  http.get<GrayscalePreview>(`/admin/models/gray-preview?group=${encodeURIComponent(groupId)}`);

/** 灰度组效果对比（各成员：weight / 请求数 / P50/P95 / 错误率 / 成本） */
export interface GrayscaleMember {
  modelId: string;
  displayName: string;
  provider: string;
  weight: number;
  enabled: boolean;
  requests: number;
  errors: number;
  errorRate: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  tokensIn: number;
  tokensOut: number;
  costCny: number;
}

export interface GrayscaleComparison {
  modelId: string;
  group: string;
  from: string;
  to: string;
  /** metrics-store | memory | none */
  source: string;
  members: GrayscaleMember[];
}

export const fetchGrayscaleComparison = (modelId: string, range = '24h') =>
  http.get<GrayscaleComparison>(
    `/admin/models/${encodeURIComponent(modelId)}/grayscale-comparison?range=${range}`,
  );
import { http } from '../request';

/**
 * 用户反馈标注 API（Round 11 §feedback-annotation）。
 *
 * 三类端点：
 * - POST /v1/feedback —— 用户/SDK 提交（X-API-Key 鉴权，走 chat 同链路）
 * - GET  /v1/feedback —— 管理端条件查询（X-Admin-Token）
 * - GET  /v1/feedback/summary —— 聚合统计
 * - GET  /v1/feedback/by-trace/{traceId} —— 按 trace 查
 *
 * 见 gateway-interfaces/feedback/FeedbackController.java。
 */

export type Sentiment = 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL';

export interface FeedbackRecord {
  id: number;
  traceId: string;
  spanId?: string;
  tenantId: string;
  userId?: string;
  model?: string;
  sentiment: Sentiment;
  score: number;
  comment: string;
  tags: string[];
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface SubmitFeedbackInput {
  tenantId: string;
  traceId: string;
  spanId?: string;
  sentiment: Sentiment | 'thumbs_up' | 'thumbs_down' | '👍' | '👎' | 'good' | 'bad';
  score?: number;
  comment?: string;
  tags?: string[];
  model?: string;
}

export interface FeedbackSummary {
  total: number;
  positive: number;
  negative: number;
  neutral: number;
  positiveRatio: number;
  withComment: number;
  byModel: Array<{ model: string; count: number; positive: number; negative: number }>;
  topTags: Array<{ tag: string; count: number }>;
}

/** 提交一条反馈标注（用户/SDK 调用,X-API-Key 鉴权）。 */
export const postFeedback = (input: SubmitFeedbackInput) =>
  http.post<{ id: number; createdAt: string; sentiment: Sentiment; traceId: string }>(
    '/feedback',
    input,
  );

export interface ListFeedbackQuery {
  tenant?: string;
  traceId?: string;
  model?: string;
  sentiment?: Sentiment;
  from?: string;
  to?: string;
  keyword?: string;
  limit?: number;
  offset?: number;
}

/** 管理端条件查询。 */
export const listFeedback = (query: ListFeedbackQuery = {}) => {
  const params = new URLSearchParams();
  params.set('tenant', query.tenant ?? 'au');
  if (query.traceId) params.set('traceId', query.traceId);
  if (query.model) params.set('model', query.model);
  if (query.sentiment) params.set('sentiment', query.sentiment);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  params.set('limit', String(query.limit ?? 50));
  params.set('offset', String(query.offset ?? 0));
  return http.get<FeedbackRecord[]>('/feedback?' + params.toString());
};

/** 按 traceId 查全部标注。 */
export const listFeedbackByTrace = (traceId: string) =>
  http.get<FeedbackRecord[]>(`/feedback/by-trace/${encodeURIComponent(traceId)}`);

/** 聚合统计。 */
export const getFeedbackSummary = (query: { tenant?: string; model?: string; from?: string; to?: string } = {}) => {
  const params = new URLSearchParams();
  params.set('tenant', query.tenant ?? 'au');
  if (query.model) params.set('model', query.model);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  return http.get<FeedbackSummary>('/feedback/summary?' + params.toString());
};

import { http } from '../request';

/**
 * agents.ts — Agent 相关 API
 *
 * - listAgents / listDiscovery：客户端与服务发现视图共用，列出已注册 Agent
 * - listRegisteredAgents：管理员视图（/agents 页面），带分页/筛选
 * - registerAgent / updateAgent / deleteAgent：CRUD（管理员操作）
 * - toggleAgentAvailability：运行时启用/禁用
 *
 * 后端契约（与 admin-api-v2 对齐）
 *   GET    /v1/admin/agents                  → AgentRegistration[]
 *   POST   /v1/admin/agents                  → AgentRegistration
 *   PUT    /v1/admin/agents/:id              → AgentRegistration
 *   DELETE /v1/admin/agents/:id              → { deleted: id }
 *   POST   /v1/admin/agents/:id/availability → AgentRegistration（切换可用状态）
 *   GET    /v1/admin/agents/check-name/:name → { available: boolean, suggestion?: string }
 *   POST   /v1/admin/agents/:id/test         → { ok, latencyMs?, message? }
 *   GET    /v1/agents                        → AgentInfo[]（公开通道）
 *   GET    /v1/admin/discovery               → AgentInfo[]（同步自 Nacos）
 *   GET    /v1/models                        → ModelInfo[]（公开模型）
 */

export interface AgentInfo {
  name: string;
  description: string;
  skills: string[];
  version?: string;
  available?: boolean;
  endpoint?: string;
}

/**
 * Admin 注册视图使用的完整结构。
 * 比 AgentInfo 多出：状态、来源、最近心跳、tag、owner、可观测信息。
 */
export interface AgentRegistration {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  version: string;
  /** 显式启停（管理员控制），与 available（运行时心跳）正交 */
  enabled: boolean;
  available: boolean;
  source: 'static' | 'nacos' | 'manual' | 'kubernetes';
  owner: string;
  tags: string[];
  skills: string[];
  lastSeenAt?: string;
  createdAt: string;
  updatedAt: string;
  /** 心跳超时（秒），默认 30 */
  heartbeatTimeoutSec?: number;
  /** 注册来源的元数据，例：nacos group / k8s namespace */
  origin?: Record<string, string>;
}

export interface AgentRegistrationInput {
  name: string;
  description: string;
  endpoint: string;
  version: string;
  enabled?: boolean;
  tags?: string[];
  skills?: string[];
  heartbeatTimeoutSec?: number;
  origin?: Record<string, string>;
}

export interface ListAgentsQuery {
  q?: string;
  source?: AgentRegistration['source'];
  enabled?: boolean;
  page?: number;
  pageSize?: number;
}

export interface Paged<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

// ───────────────────────── public / discovery ─────────────────────────
export const listAgents = () => http.get<AgentInfo[]>('/agents');
export const listDiscovery = () => http.get<AgentInfo[]>('/admin/discovery');

export interface ModelInfo {
  modelId: string;
  displayName?: string;
  provider?: string;
}
export const listPublicModels = () => http.get<ModelInfo[]>('/models');

// ───────────────────────── admin CRUD ─────────────────────────
export const listRegisteredAgents = (q: ListAgentsQuery = {}) =>
  http.get<Paged<AgentRegistration>>('/admin/agents', { params: q as Record<string, unknown> });

export const getRegisteredAgent = (id: string) =>
  http.get<AgentRegistration>(`/admin/agents/${encodeURIComponent(id)}`);

export const registerAgent = (body: AgentRegistrationInput) =>
  http.post<AgentRegistration>('/admin/agents', body);

export const updateAgent = (id: string, body: Partial<AgentRegistrationInput>) =>
  http.put<AgentRegistration>(
    `/admin/agents/${encodeURIComponent(id)}`,
    body,
  );

export const deleteAgent = (id: string) =>
  http.delete<{ deleted: string }>(`/admin/agents/${encodeURIComponent(id)}`);

export const toggleAgentAvailability = (id: string, enabled: boolean) =>
  http.post<AgentRegistration>(`/admin/agents/${encodeURIComponent(id)}/availability`, {
    enabled,
  });

export const checkAgentName = (name: string) =>
  http.get<{ available: boolean; suggestion?: string }>(
    `/admin/agents/check-name/${encodeURIComponent(name)}`,
  );

export const testAgentConnection = (id: string) =>
  http.post<{ ok: boolean; latencyMs?: number; message?: string }>(
    `/admin/agents/${encodeURIComponent(id)}/test`,
  {},
  );

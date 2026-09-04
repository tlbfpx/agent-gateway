/**
 * tests/fixtures/seed.ts
 *
 * 生产级 UI 测试用的种子数据。所有菜单页对应的接口都给一份确定性的 fixture，
 * 让测试不依赖后端，又贴近真实返回 shape。
 *
 * 设计原则：
 *  1. 每个字段都对齐真实 API 返回，避免类型漂移
 *  2. 时间字段用确定性偏移（"现在 - N 小时"），便于快照与排序
 *  3. 提供 reset() 出口，便于在 beforeEach 重置整个 store
 */

import type { AgentRegistration } from '../../src/lib/api/agents';
import type { TraceSummary, SpanRecord } from '../../src/lib/api/traces';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const now = Date.now();
const ago = (ms: number) => new Date(now - ms).toISOString();
const ahead = (ms: number) => new Date(now + ms).toISOString();

// ─────────── Models ───────────
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
  grayWeight?: number;
}
export const seedModels: Model[] = [
  {
    id: 'gpt-4o',
    provider: 'openai',
    displayName: 'GPT-4o',
    modelName: 'gpt-4o',
    endpoint: 'https://api.openai.com/v1',
    apiKeyMasked: 'sk-****aB12',
    capabilities: ['chat', 'vision', 'tools'],
    contextWindow: 128_000,
    enabled: true,
    grayWeight: 0,
  },
  {
    id: 'claude-3.7',
    provider: 'anthropic',
    displayName: 'Claude 3.7 Sonnet',
    modelName: 'claude-3-7-sonnet',
    endpoint: 'https://api.anthropic.com',
    apiKeyMasked: 'sk-ant-****2f4a',
    capabilities: ['chat', 'tools', 'long-ctx'],
    contextWindow: 200_000,
    enabled: true,
    grayWeight: 30,
  },
  {
    id: 'qwen-max',
    provider: 'aliyun',
    displayName: '通义千问 Max',
    modelName: 'qwen-max',
    endpoint: 'https://dashscope.aliyuncs.com',
    apiKeyMasked: 'sk-****d11c',
    capabilities: ['chat', 'tools'],
    contextWindow: 32_000,
    enabled: true,
  },
  {
    id: 'deepseek-r1',
    provider: 'deepseek',
    displayName: 'DeepSeek R1',
    modelName: 'deepseek-r1',
    endpoint: 'https://api.deepseek.com',
    capabilities: ['chat', 'reasoning'],
    contextWindow: 64_000,
    enabled: false,
  },
];

// ─────────── API Keys ───────────
export interface ApiKey {
  id: string;
  owner: string;
  tenant: string;
  enabled: boolean;
  models?: string[];
  rateLimitRpm?: number;
  expiresAt?: string;
  createdAt: string;
  lastUsedAt?: string;
}
export const seedApiKeys: ApiKey[] = [
  {
    id: 'pk_live_01HMZT7W3JKAQ',
    owner: 'admin',
    tenant: 'primary',
    enabled: true,
    models: ['gpt-4o', 'claude-3.7'],
    rateLimitRpm: 600,
    createdAt: ago(30 * DAY),
    lastUsedAt: ago(2 * HOUR),
  },
  {
    id: 'pk_live_01HMZT7W3JKQB',
    owner: 'service-bot',
    tenant: 'tenant-b',
    enabled: true,
    rateLimitRpm: 120,
    createdAt: ago(60 * DAY),
    lastUsedAt: ago(8 * HOUR),
  },
  {
    id: 'pk_live_01HMZT7W3JKQC',
    owner: 'ci-runner',
    tenant: 'primary',
    enabled: false,
    createdAt: ago(15 * DAY),
  },
];

// ─────────── Webhooks ───────────
export interface Webhook {
  id: string;
  url: string;
  events: string[];
  enabled: boolean;
  tenant: string;
  secret?: string;
  /** 死信队列条目数 */
  dlqCount?: number;
}
export const seedWebhooks: Webhook[] = [
  {
    id: 'wh_1',
    url: 'https://hooks.primary.example/event',
    events: ['chat.completed', 'rbac.denied'],
    enabled: true,
    tenant: 'primary',
    secret: 'whsec_****3a91',
    dlqCount: 0,
  },
  {
    id: 'wh_2',
    url: 'https://audit.example/in',
    events: ['audit.appended'],
    enabled: true,
    tenant: 'primary',
    dlqCount: 4,
  },
];

// ─────────── Audit ───────────
export interface AuditEntry {
  id: string;
  ts: string;
  actor: string;
  type: 'auth' | 'config' | 'key' | 'rbac' | 'chat';
  action: string;
  resource: string;
  result: 'success' | 'fail' | 'deny' | 'allow';
  reason?: string;
}
export const seedAudit: AuditEntry[] = [
  {
    id: 'ae_001',
    ts: ago(15 * 60_000),
    actor: 'admin@primary',
    type: 'config',
    action: 'config.update',
    resource: 'models/gpt-4o',
    result: 'success',
  },
  {
    id: 'ae_002',
    ts: ago(2 * HOUR),
    actor: 'admin@primary',
    type: 'key',
    action: 'api-key.create',
    resource: 'pk_live_01HMZT7W3JKAQ',
    result: 'success',
  },
  {
    id: 'ae_003',
    ts: ago(3 * HOUR),
    actor: 'service-bot@tenant-b',
    type: 'rbac',
    action: 'rbac.preview',
    resource: 'tenant-b/models/claude-3.7',
    result: 'deny',
    reason: 'cross-tenant 隔离策略拒绝',
  },
  {
    id: 'ae_004',
    ts: ago(8 * HOUR),
    actor: 'admin@primary',
    type: 'chat',
    action: 'chat.invoke',
    resource: 'gpt-4o',
    result: 'success',
  },
];

// ─────────── Config Versions ───────────
export interface ConfigVersion {
  name: string;
  version: number;
  ts: string;
  author: string;
  summary: string;
}
export const seedConfigVersions: ConfigVersion[] = [
  { name: 'models', version: 17, ts: ago(1 * HOUR), author: 'admin@primary', summary: '灰度 gpt-4o 30%' },
  { name: 'models', version: 16, ts: ago(2 * DAY), author: 'admin@primary', summary: '下线 deepseek-r1' },
  { name: 'models', version: 15, ts: ago(7 * DAY), author: 'admin@primary', summary: '启用 qwen-max' },
  { name: 'api-keys', version: 4, ts: ago(3 * DAY), author: 'admin@primary', summary: '新增 service-bot' },
];

// ─────────── RBAC ───────────
export interface RbacVerdict {
  allowed: boolean;
  reason?: string;
}
export const seedRbacAllow: RbacVerdict = { allowed: true };
export const seedRbacDeny: RbacVerdict = {
  allowed: false,
  reason: 'cross-tenant 隔离策略拒绝',
};

// ─────────── Agents (admin view) ───────────
export const seedAgents: AgentRegistration[] = [
  {
    id: 'ag_weather',
    name: 'weather-mcp',
    description: '天气查询 MCP Server',
    endpoint: 'https://agents.example/weather',
    version: '1.4.2',
    enabled: true,
    available: true,
    source: 'nacos',
    owner: 'admin@primary',
    tags: ['mcp', 'tool'],
    skills: ['weather', 'forecast'],
    lastSeenAt: ago(2 * 60_000),
    createdAt: ago(90 * DAY),
    updatedAt: ago(2 * HOUR),
    heartbeatTimeoutSec: 30,
    origin: { namespace: 'tools', group: 'DEFAULT_GROUP' },
  },
  {
    id: 'ag_search',
    name: 'web-search',
    description: '联网检索 Agent',
    endpoint: 'https://agents.example/search',
    version: '2.0.1',
    enabled: true,
    available: true,
    source: 'manual',
    owner: 'admin@primary',
    tags: ['search'],
    skills: ['search', 'summarize'],
    lastSeenAt: ago(45_000),
    createdAt: ago(30 * DAY),
    updatedAt: ago(1 * DAY),
    heartbeatTimeoutSec: 30,
  },
  {
    id: 'ag_sql',
    name: 'sql-expert',
    description: 'SQL 专家',
    endpoint: 'https://agents.example/sql',
    version: '1.0.5',
    enabled: false,
    available: false,
    source: 'kubernetes',
    owner: 'team-data@primary',
    tags: ['db', 'tool'],
    skills: ['sql', 'analysis'],
    lastSeenAt: ago(2 * HOUR),
    createdAt: ago(200 * DAY),
    updatedAt: ago(2 * DAY),
    heartbeatTimeoutSec: 60,
    origin: { namespace: 'data', pod: 'sql-7d5' },
  },
  {
    id: 'ag_pdf',
    name: 'pdf-reader',
    description: 'PDF 解析助手',
    endpoint: 'https://agents.example/pdf',
    version: '0.9.3',
    enabled: true,
    available: true,
    source: 'static',
    owner: 'admin@primary',
    tags: ['doc'],
    skills: ['pdf', 'extract'],
    lastSeenAt: ago(5 * 60_000),
    createdAt: ago(10 * DAY),
    updatedAt: ago(10 * DAY),
    heartbeatTimeoutSec: 30,
  },
];

// ─────────── Health / Readiness ───────────
export interface HealthReport {
  status: 'UP' | 'DOWN';
}
export const seedHealth: HealthReport = {
  status: 'UP',
};

export interface ReadyReport {
  status: 'READY' | 'NOT_READY';
  checks: Record<string, { status: string; details?: Record<string, unknown> }>;
}
export const seedReady: ReadyReport = {
  status: 'READY',
  checks: {
    db: { status: 'UP', details: { latencyMs: 12, pool: 8 } },
    redis: { status: 'UP', details: { memory: '24MB' } },
    nacos: { status: 'UP', details: { cluster: 'shanghai-1' } },
    webhook: { status: 'UP' },
  },
};

// ─────────── Chat ───────────
export interface ChatSession {
  id: string;
  title: string;
  lastActiveAt: string;
}
export const seedChatSessions: ChatSession[] = [
  { id: 's_001', title: '问一下北京天气', lastActiveAt: ago(2 * HOUR) },
  { id: 's_002', title: 'SQL 优化建议', lastActiveAt: ago(1 * DAY) },
];

// ─────────── 提示缓存（gateway.llm.prompt-cache，后端并行开发契约）───────────
export interface PromptCacheState {
  enabled: boolean;
  ttl: string;
  maxEntries: number;
  /** prompt_cache_hit_total 计数 */
  hits: number;
  /** prompt_cache_miss_total 计数 */
  misses: number;
}
export const seedPromptCache: PromptCacheState = {
  enabled: true,
  ttl: '10m',
  maxEntries: 1000,
  hits: 42,
  misses: 18,
};

// ─────────── Settings (no fixtures needed beyond defaults) ───────────
// (Settings 主要落 localStorage，状态由 setApiKey/setTenant 控制)

// ─────────── Traces (调用链追踪) ───────────
// 4 条确定性 trace：1 正常 gateway.chat / 1 慢链路 llm.call / 1 错误 agent.call / 1 auth.verify
// 时间字段全部用 ago() 偏移，避免 Date.now() 漂移。
export const seedTraces: TraceSummary[] = [
  {
    traceId: 'tid-0001-gateway-chat-ok',
    rootSpanName: 'gateway.chat',
    startTime: ago(5 * 60_000),
    totalDurationMs: 820,
    spanCount: 3,
    errorCount: 0,
    agentNames: [],
  },
  {
    traceId: 'tid-0002-llm-call-slow',
    rootSpanName: 'gateway.chat',
    startTime: ago(35 * 60_000),
    totalDurationMs: 6_420,
    spanCount: 3,
    errorCount: 0,
    agentNames: ['weather-mcp'],
  },
  {
    traceId: 'tid-0003-agent-call-error',
    rootSpanName: 'gateway.chat',
    startTime: ago(90 * 60_000),
    totalDurationMs: 1_230,
    spanCount: 3,
    errorCount: 1,
    agentNames: ['sql-expert'],
  },
  {
    traceId: 'tid-0004-auth-verify',
    rootSpanName: 'auth.verify',
    startTime: ago(2 * HOUR),
    totalDurationMs: 38,
    spanCount: 3,
    errorCount: 0,
    agentNames: [],
  },
];

// 每个 traceId 对应 ≥3 个 span：1 SERVER 父 + 1 CLIENT agent.call + 1 INTERNAL llm.call
// 错误链路 span 含 status='ERROR' 与 attributes.agent_name，便于详情页断言
export const seedSpans: Record<string, SpanRecord[]> = {
  'tid-0001-gateway-chat-ok': [
    {
      traceId: 'tid-0001-gateway-chat-ok',
      spanId: 's1-parent',
      parentSpanId: null,
      name: 'gateway.chat',
      kind: 'SERVER',
      startTime: ago(5 * 60_000),
      endTime: ago(5 * 60_000 - 820),
      durationMs: 820,
      status: 'OK',
      attributes: { 'http.method': 'POST', 'http.route': '/v1/chat', tenant: 'primary' },
      events: [],
    },
    {
      traceId: 'tid-0001-gateway-chat-ok',
      spanId: 's1-agent',
      parentSpanId: 's1-parent',
      name: 'agent.call',
      kind: 'CLIENT',
      startTime: ago(5 * 60_000 - 40),
      endTime: ago(5 * 60_000 - 180),
      durationMs: 140,
      status: 'OK',
      attributes: { agent_name: 'weather-mcp', provider: 'http' },
      events: [],
    },
    {
      traceId: 'tid-0001-gateway-chat-ok',
      spanId: 's1-llm',
      parentSpanId: 's1-parent',
      name: 'llm.call',
      kind: 'INTERNAL',
      startTime: ago(5 * 60_000 - 200),
      endTime: ago(5 * 60_000 - 820),
      durationMs: 620,
      status: 'OK',
      attributes: { provider: 'openai', model: 'gpt-4o', tokens_in: '120', tokens_out: '36' },
      events: [],
    },
  ],
  'tid-0002-llm-call-slow': [
    {
      traceId: 'tid-0002-llm-call-slow',
      spanId: 's2-parent',
      parentSpanId: null,
      name: 'gateway.chat',
      kind: 'SERVER',
      startTime: ago(35 * 60_000),
      endTime: ago(35 * 60_000 - 6_420),
      durationMs: 6_420,
      status: 'OK',
      attributes: { 'http.method': 'POST', 'http.route': '/v1/chat', tenant: 'primary' },
      events: [],
    },
    {
      traceId: 'tid-0002-llm-call-slow',
      spanId: 's2-agent',
      parentSpanId: 's2-parent',
      name: 'agent.call',
      kind: 'CLIENT',
      startTime: ago(35 * 60_000 - 50),
      endTime: ago(35 * 60_000 - 220),
      durationMs: 170,
      status: 'OK',
      attributes: { agent_name: 'weather-mcp', provider: 'http' },
      events: [],
    },
    {
      traceId: 'tid-0002-llm-call-slow',
      spanId: 's2-llm',
      parentSpanId: 's2-parent',
      name: 'llm.call',
      kind: 'INTERNAL',
      startTime: ago(35 * 60_000 - 240),
      endTime: ago(35 * 60_000 - 6_420),
      durationMs: 6_180,
      status: 'OK',
      attributes: { provider: 'anthropic', model: 'claude-3-7-sonnet', tokens_in: '480', tokens_out: '210' },
      events: [],
    },
  ],
  'tid-0003-agent-call-error': [
    {
      traceId: 'tid-0003-agent-call-error',
      spanId: 's3-parent',
      parentSpanId: null,
      name: 'gateway.chat',
      kind: 'SERVER',
      startTime: ago(90 * 60_000),
      endTime: ago(90 * 60_000 - 1_230),
      durationMs: 1_230,
      status: 'OK',
      attributes: { 'http.method': 'POST', 'http.route': '/v1/chat', tenant: 'primary' },
      events: [],
    },
    {
      traceId: 'tid-0003-agent-call-error',
      spanId: 's3-agent',
      parentSpanId: 's3-parent',
      name: 'agent.call',
      kind: 'CLIENT',
      startTime: ago(90 * 60_000 - 60),
      endTime: ago(90 * 60_000 - 1_200),
      durationMs: 1_140,
      status: 'ERROR',
      attributes: { agent_name: 'sql-expert', provider: 'http', error: 'connection timeout (30s)' },
      events: [
        { time: ago(90 * 60_000 - 800), name: 'exception', attributes: { type: 'TimeoutError' } },
      ],
    },
    {
      traceId: 'tid-0003-agent-call-error',
      spanId: 's3-llm',
      parentSpanId: 's3-parent',
      name: 'llm.call',
      kind: 'INTERNAL',
      startTime: ago(90 * 60_000 - 1_205),
      endTime: ago(90 * 60_000 - 1_230),
      durationMs: 25,
      status: 'OK',
      attributes: { provider: 'openai', model: 'gpt-4o', tokens_in: '64', tokens_out: '0' },
      events: [],
    },
  ],
  'tid-0004-auth-verify': [
    {
      traceId: 'tid-0004-auth-verify',
      spanId: 's4-parent',
      parentSpanId: null,
      name: 'auth.verify',
      kind: 'SERVER',
      startTime: ago(2 * HOUR),
      endTime: ago(2 * HOUR - 38),
      durationMs: 38,
      status: 'OK',
      attributes: { 'http.method': 'POST', 'http.route': '/v1/auth/verify', tenant: 'primary' },
      events: [],
    },
    {
      traceId: 'tid-0004-auth-verify',
      spanId: 's4-agent',
      parentSpanId: 's4-parent',
      name: 'agent.call',
      kind: 'CLIENT',
      startTime: ago(2 * HOUR - 5),
      endTime: ago(2 * HOUR - 20),
      durationMs: 15,
      status: 'OK',
      attributes: { agent_name: 'web-search', provider: 'http' },
      events: [],
    },
    {
      traceId: 'tid-0004-auth-verify',
      spanId: 's4-llm',
      parentSpanId: 's4-parent',
      name: 'llm.call',
      kind: 'INTERNAL',
      startTime: ago(2 * HOUR - 22),
      endTime: ago(2 * HOUR - 38),
      durationMs: 16,
      status: 'OK',
      attributes: { provider: 'openai', model: 'gpt-4o', tokens_in: '8', tokens_out: '4' },
      events: [],
    },
  ],
};

// ─────────── reset helper ───────────
export const seedStore = {
  models: seedModels,
  apiKeys: seedApiKeys,
  webhooks: seedWebhooks,
  audit: seedAudit,
  configVersions: seedConfigVersions,
  agents: seedAgents,
  health: seedHealth,
  ready: seedReady,
  chatSessions: seedChatSessions,
  promptCache: seedPromptCache,
  traces: seedTraces,
  spans: seedSpans,
};
export type SeedStore = typeof seedStore;

/** 深拷贝整个 store，返回新对象以便测试隔离 */
export function resetSeed(): SeedStore {
  return JSON.parse(JSON.stringify(seedStore));
}

export const EXPIRES_AT_SAMPLE = ahead(30 * DAY).slice(0, 10); // 'YYYY-MM-DD'

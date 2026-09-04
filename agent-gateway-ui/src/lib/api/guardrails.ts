import { request } from '../request';

export type GuardrailMode = 'OBSERVE' | 'BLOCK' | 'REDACT';

export interface GuardrailPolicy {
  mode: GuardrailMode;
  toxicityKeywords: string[];
  piiPatterns: string[];
  jailbreakPatterns: string[];
  toolAllowList: string[];
  toolBlockList: string[];
}

export interface GuardrailStats {
  status: string;
  hits?: number;
  blocks?: number;
}

export const guardrailsApi = {
  currentPolicy: () => request<GuardrailPolicy>('/admin/guardrails/policy'),
  updatePolicy: (policy: GuardrailPolicy) =>
    request<{ status: string; mode: string }>('/admin/guardrails/policy', {
      method: 'POST',
      body: JSON.stringify(policy),
    }),
  stats: () => request<GuardrailStats>('/admin/guardrails/stats'),
};